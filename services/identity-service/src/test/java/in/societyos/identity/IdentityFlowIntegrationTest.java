package in.societyos.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.societyos.identity.platform.core.UuidV7;
import in.societyos.identity.platform.test.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End to end inside identity-service: real Postgres (RLS on), Kafka and Redis. */
class IdentityFlowIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper json;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
            .build();
  }

  @Test
  void otpLoginIssuesVerifiableTokensAndRefreshRotates() {
    String phone = "+9198" + (10_000_000 + (int) (Math.random() * 89_999_999));

    JsonNode requested = post("/v1/auth/otp/request", Map.of("phone", phone), null);
    assertThat(requested.path("expiresInSeconds").asLong()).isEqualTo(300);

    JsonNode wrong = post("/v1/auth/otp/verify", verifyBody(phone, "000000"), null);
    assertThat(wrong.path("code").asString()).isEqualTo("OTP_INVALID");

    JsonNode login = post("/v1/auth/otp/verify", verifyBody(phone, "123456"), null);
    assertThat(login.path("newUser").asBoolean()).isTrue();
    String access = login.path("accessToken").asString();
    String refresh = login.path("refreshToken").asString();
    assertThat(access).isNotBlank();

    // The token verifies against identity's own JWKS and /v1/me works with it
    JsonNode jwks = get("/.well-known/jwks.json", null);
    assertThat(jwks.path("keys").get(0).path("kty").asString()).isEqualTo("RSA");
    JsonNode me = get("/v1/me", access);
    assertThat(me.path("phone").asString()).isEqualTo(phone);

    // Refresh rotates; replaying the old refresh token revokes the session
    JsonNode rotated = post("/v1/auth/token/refresh", Map.of("refreshToken", refresh), null);
    assertThat(rotated.path("refreshToken").asString()).isNotEqualTo(refresh);
    JsonNode reused = post("/v1/auth/token/refresh", Map.of("refreshToken", refresh), null);
    assertThat(reused.path("code").asString()).isEqualTo("REFRESH_TOKEN_REUSED");
    JsonNode afterReuse = post("/v1/auth/token/refresh", Map.of("refreshToken", rotated.path("refreshToken").asString()), null);
    assertThat(afterReuse.path("code").asString()).isEqualTo("REFRESH_TOKEN_REUSED");

    // Registration event reached the outbox
    assertThat(countRows("select count(*) from outbox_event where type = 'identity.user.registered'")).isPositive();
  }

  @Test
  void societyCreatedEventProvisionsRolesAndMembershipGrantsResidentRole() throws Exception {
    UUID societyA = UuidV7.next();
    UUID societyB = UuidV7.next();
    sendSocietyEvent(societyA, "society.created", Map.of("societyId", societyA, "name", "Green Meadows"));
    sendSocietyEvent(societyB, "society.created", Map.of("societyId", societyB, "name", "Blue Ridge"));

    await(() -> countRows("select count(*) from role where society_id = '" + societyA + "'") >= 12);
    await(() -> countRows("select count(*) from role where society_id = '" + societyB + "'") >= 12);

    // A resident logs in, then society-service reports their membership
    String phone = "+9197" + (10_000_000 + (int) (Math.random() * 89_999_999));
    post("/v1/auth/otp/request", Map.of("phone", phone), null);
    JsonNode first = post("/v1/auth/otp/verify", verifyBody(phone, "123456"), null);
    UUID userId = UUID.fromString(first.path("userId").asString());
    assertThat(first.path("societies").size()).isZero();

    UUID membershipId = UuidV7.next();
    sendSocietyEvent(
        societyA,
        "society.membership.created",
        Map.of("membershipId", membershipId, "flatId", UuidV7.next(), "userId", userId, "kind", "OWNER"));
    await(() -> countRows("select count(*) from role_assignment where user_id = '" + userId + "' and revoked_at is null") == 1);

    // Next token carries the society and the resident role; permissions resolve for it
    JsonNode refreshed = post("/v1/auth/token/refresh", Map.of("refreshToken", first.path("refreshToken").asString()), null);
    assertThat(refreshed.path("activeSocietyId").asString()).isEqualTo(societyA.toString());
    assertThat(refreshed.path("societies").get(0).path("roles").get(0).asString()).isEqualTo("RESIDENT_OWNER");
    String access = refreshed.path("accessToken").asString();
    JsonNode perms = get("/v1/me/permissions", access);
    assertThat(perms.path("permissions").toString()).contains("gatepass:create").doesNotContain("bill:generate");

    // Society B is not in the token: the platform rejects it before any controller runs
    JsonNode denied = getWithSociety("/v1/me/permissions", access, societyB);
    assertThat(denied.path("code").asString()).isEqualTo("SOCIETY_NOT_ALLOWED");

    // Membership ended → role revoked
    sendSocietyEvent(
        societyA,
        "society.membership.ended",
        Map.of("membershipId", membershipId, "flatId", UuidV7.next(), "userId", userId, "kind", "OWNER"));
    await(() -> countRows("select count(*) from role_assignment where user_id = '" + userId + "' and revoked_at is null") == 0);
  }

  @Test
  void rowLevelSecurityHidesOtherSocietiesFromTheAppRole() throws Exception {
    UUID societyA = UuidV7.next();
    UUID societyB = UuidV7.next();
    try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = owner.createStatement()) {
      for (UUID soc : List.of(societyA, societyB)) {
        s.execute("insert into role (id, society_id, code, name) values ('" + UuidV7.next() + "', '" + soc + "', 'RLS_TEST', 'x')");
      }
    }
    try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app", "app")) {
      app.setAutoCommit(false);
      try (Statement s = app.createStatement()) {
        // No tenant set: nothing is visible
        assertThat(count(s, "select count(*) from role where code = 'RLS_TEST'")).isZero();
        s.execute("select set_config('app.society_ids', '{" + societyA + "}', true), set_config('app.write_society_id', '" + societyA + "', true)");
        assertThat(count(s, "select count(*) from role where code = 'RLS_TEST'")).isEqualTo(1);
        assertThat(count(s, "select count(*) from role where society_id = '" + societyB + "'")).isZero();
        // Writing into another society is rejected by WITH CHECK
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> s.execute("insert into role (id, society_id, code, name) values ('" + UuidV7.next() + "', '" + societyB + "', 'X', 'x')"))
            .hasMessageContaining("row-level security");
      }
      app.rollback();
    }
  }

  @Test
  void serviceTokenReadsContactButUserTokenCannot() {
    String phone = "+9196" + (10_000_000 + (int) (Math.random() * 89_999_999));
    post("/v1/auth/otp/request", Map.of("phone", phone), null);
    JsonNode login = post("/v1/auth/otp/verify", verifyBody(phone, "123456"), null);
    String userId = login.path("userId").asString();
    String userToken = login.path("accessToken").asString();

    JsonNode wrongSecret =
        post("/v1/auth/service-token", Map.of("clientId", "notification-service", "clientSecret", "nope"), null);
    assertThat(wrongSecret.path("code").asString()).isEqualTo("INVALID_CLIENT");

    JsonNode service =
        post(
            "/v1/auth/service-token",
            Map.of("clientId", "notification-service", "clientSecret", "notification-local-dev-secret"),
            null);
    String serviceToken = service.path("accessToken").asString();
    assertThat(serviceToken).isNotBlank();

    JsonNode contact = get("/v1/internal/users/" + userId + "/contact", serviceToken);
    assertThat(contact.path("phone").asString()).isEqualTo(phone);

    var denied =
        http.get().uri("/v1/internal/users/" + userId + "/contact").header("Authorization", "Bearer " + userToken)
            .retrieve().toBodilessEntity();
    assertThat(denied.getStatusCode().value()).isEqualTo(403);
  }

  // --- helpers ---------------------------------------------------------------------------

  private Map<String, Object> verifyBody(String phone, String code) {
    return Map.of("phone", phone, "code", code, "device", Map.of("platform", "ANDROID", "name", "Test phone"));
  }

  private void sendSocietyEvent(UUID societyId, String type, Map<String, Object> data) throws Exception {
    UUID id = UuidV7.next();
    Map<String, Object> envelope =
        Map.of(
            "specversion", "1.0", "id", id, "source", "society-service", "type", type,
            "time", Instant.now().toString(), "subject", "test", "societyid", societyId,
            "actortype", "SYSTEM", "data", data);
    var record = new ProducerRecord<>("sos.society.events.v1", societyId.toString(), json.writeValueAsString(envelope));
    record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
    record.headers().add("ce_id", id.toString().getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get();
  }

  private JsonNode post(String path, Object body, String token) {
    var spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
    if (token != null) {
      spec = spec.header("Authorization", "Bearer " + token);
    }
    return json.readTree(spec.retrieve().body(String.class));
  }

  private JsonNode get(String path, String token) {
    var spec = http.get().uri(path);
    if (token != null) {
      spec = spec.header("Authorization", "Bearer " + token);
    }
    return json.readTree(spec.retrieve().body(String.class));
  }

  private JsonNode getWithSociety(String path, String token, UUID society) {
    return json.readTree(
        http.get().uri(path).header("Authorization", "Bearer " + token).header("X-Society-Id", society.toString())
            .retrieve().body(String.class));
  }

  private static long countRows(String sql) {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = c.createStatement()) {
      return count(s, sql);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static long count(Statement s, String sql) throws java.sql.SQLException {
    try (ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Condition not met within 30 s");
  }
}
