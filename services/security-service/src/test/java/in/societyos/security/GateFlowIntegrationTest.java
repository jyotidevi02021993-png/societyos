package in.societyos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.security.platform.core.UuidV7;
import in.societyos.security.platform.test.IntegrationTestBase;
import in.societyos.security.platform.test.TestJwtIssuer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gate end to end inside security-service: real Postgres (RLS on, app role), Kafka (society
 * and identity events in, outbox relayed out), Redis (permission cache, pending approvals).
 */
class GateFlowIntegrationTest extends IntegrationTestBase {

  static final String[] GUARD = {"gate:entry", "gate:log-view", "staff:attendance", "sos:raise", "incident:report"};
  static final String[] RESIDENT = {"gatepass:create", "gatepass:view", "gate:approve", "sos:raise"};

  @LocalServerPort int port;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper json;
  @Autowired StringRedisTemplate redis;

  RestClient http;

  UUID society;
  UUID flat;
  UUID guard;
  UUID resident;
  String guardToken;
  String residentToken;

  @BeforeEach
  void setUp() throws Exception {
    http = RestClient.builder().baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();
    society = UuidV7.next();
    flat = UuidV7.next();
    guard = UuidV7.next();
    resident = UuidV7.next();
    guardToken = TestJwtIssuer.token(guard, society, "GUARD");
    residentToken = TestJwtIssuer.token(resident, society, "RESIDENT_OWNER");

    // The society copy is built from society-service events, as in production.
    sendEvent("sos.society.events.v1", society, "society.settings.updated",
        Map.of("societyId", society, "settings", Map.of("gateApprovalTimeoutSeconds", 10, "visitorRetentionDays", 90)));
    sendEvent("sos.society.events.v1", society, "society.flat.created", Map.of("flatId", flat, "towerId", UuidV7.next(),
        "towerName", "A", "number", "1203", "label", "A-1203", "floor", 12, "status", "OCCUPIED"));
    sendEvent("sos.society.events.v1", society, "society.membership.created", Map.of("membershipId", UuidV7.next(),
        "flatId", flat, "userId", resident, "residentId", UuidV7.next(), "residentName", "Asha Rao", "kind", "OWNER",
        "isPrimary", true));
    await(() -> count("select count(*) from flat_resident where flat_id = '" + flat + "'") == 1);
    await(() -> count("select count(*) from flat_directory where id = '" + flat + "'") == 1);
    await(() -> count("select count(*) from society_settings where id = '" + society
        + "' and gate_approval_timeout_seconds = 10 and visitor_retention_days = 90") == 1);
  }

  @Test
  void walkInIsApprovedCheckedInAndOutWithEventsAndMaskedPhone() throws Exception {
    JsonNode entry = requestEntry("Ramesh", "98765 43210", "DELIVERY");
    assertThat(entry.path("status").asString()).isEqualTo("REQUESTED");
    assertThat(entry.path("flatLabel").asString()).isEqualTo("A-1203");
    assertThat(entry.path("visitor").path("phoneMasked").asString()).isEqualTo("98XXXXXX10");
    Instant requested = Instant.parse(entry.path("requestedAt").asString());
    assertThat(Instant.parse(entry.path("expiresAt").asString())).isEqualTo(requested.plusSeconds(10));
    UUID id = UUID.fromString(entry.path("id").asString());

    // Outbox: the catalogue event with the resident as audience, plus an urgent notification; no phone anywhere.
    String requestedEvent = outbox("security.entry.requested", id);
    assertThat(requestedEvent).contains(resident.toString()).contains("A-1203").doesNotContain("9876543210");
    assertThat(count("select count(*) from outbox_event where type = 'security.notification.requested' and payload::text like '%"
        + id + "%' and payload::text like '%HIGH%'")).isEqualTo(1);
    assertThat(count("select count(*) from visitor where phone_enc like 'v1:%' and phone_enc not like '%9876543210%' and id = '"
        + entry.path("visitor").path("id").asString() + "'")).isEqualTo(1);
    assertThat(redis.hasKey("gate:pending:" + society + ":" + id)).isTrue();

    // The guard console lists it; the resident approves.
    acting(GUARD);
    assertThat(get("/v1/entries/pending", guardToken).toString()).contains(id.toString());
    acting(RESIDENT);
    JsonNode approved = post("/v1/entries/" + id + "/decision", Map.of("decision", "APPROVE"), residentToken);
    assertThat(approved.path("status").asString()).isEqualTo("APPROVED");
    assertThat(approved.path("decidedBy").asString()).isEqualTo(resident.toString());
    assertThat(outbox("security.entry.approved", id)).contains(resident.toString());
    assertThat(redis.hasKey("gate:pending:" + society + ":" + id)).isFalse();

    acting(GUARD);
    assertThat(post("/v1/entries/" + id + "/check-in", Map.of(), guardToken).path("status").asString()).isEqualTo("IN");
    assertThat(post("/v1/entries/" + id + "/check-out", Map.of(), guardToken).path("status").asString()).isEqualTo("OUT");
    assertThat(outbox("security.entry.checked_in", id)).contains("DELIVERY");
    assertThat(outbox("security.entry.checked_out", id)).isNotEmpty();

    // The resident sees it in their flat log.
    acting(RESIDENT);
    JsonNode log = get("/v1/entries", residentToken);
    assertThat(log.path("items").toString()).contains(id.toString());
  }

  @Test
  void denyIsFinalAndOnlyResidentsOfTheFlatDecide() throws Exception {
    UUID id = UUID.fromString(requestEntry("Unknown", null, "GUEST").path("id").asString());

    UUID stranger = UuidV7.next();
    acting(RESIDENT);
    JsonNode forbidden = post("/v1/entries/" + id + "/decision", Map.of("decision", "APPROVE"),
        TestJwtIssuer.token(stranger, society, "RESIDENT_OWNER"));
    assertThat(forbidden.path("code").asString()).isEqualTo("NOT_YOUR_FLAT");

    JsonNode denied = post("/v1/entries/" + id + "/decision", Map.of("decision", "DENY"), residentToken);
    assertThat(denied.path("status").asString()).isEqualTo("DENIED");
    assertThat(outbox("security.entry.denied", id)).isNotEmpty();
    JsonNode again = post("/v1/entries/" + id + "/decision", Map.of("decision", "APPROVE"), residentToken);
    assertThat(again.path("code").asString()).isEqualTo("ENTRY_ALREADY_DECIDED");

    acting(GUARD);
    assertThat(post("/v1/entries/" + id + "/check-in", Map.of(), guardToken).path("code").asString())
        .isEqualTo("ENTRY_NOT_APPROVED");
  }

  @Test
  void unansweredRequestExpiresAfterTheSocietyTimeout() throws Exception {
    UUID id = UUID.fromString(requestEntry("Late Visitor", null, "CAB").path("id").asString());
    // gateApprovalTimeoutSeconds = 10 from society.settings.updated; db-scheduler polls every second.
    await(() -> count("select count(*) from entry_log where id = '" + id + "' and status = 'EXPIRED'") == 1,
        Duration.ofSeconds(40));
    assertThat(outbox("security.entry.expired", id)).contains(flat.toString());
    acting(RESIDENT);
    JsonNode late = post("/v1/entries/" + id + "/decision", Map.of("decision", "APPROVE"), residentToken);
    assertThat(late.path("code").asString()).isEqualTo("ENTRY_ALREADY_DECIDED");
  }

  @Test
  void gatePassOtpAdmitsWithinUsesAndCodesStayWithResidents() throws Exception {
    acting(RESIDENT);
    Instant validTo = Instant.now().plus(Duration.ofHours(3));
    JsonNode pass = post("/v1/gatepasses", Map.of("flatId", flat, "kind", "GUEST", "guestName", "Ravi",
        "validTo", validTo.toString(), "maxUses", 2), residentToken);
    String code = pass.path("code").asString();
    assertThat(code).matches("\\d{6}");
    assertThat(pass.path("qrToken").asString()).isNotBlank();
    UUID passId = UUID.fromString(pass.path("id").asString());
    assertThat(outbox("security.pass.created", passId)).doesNotContain(code);
    assertThat(get("/v1/gatepasses/" + passId + "/share", residentToken).path("message").asString()).contains(code);

    acting(GUARD);
    JsonNode seenByGuard = get("/v1/gatepasses/" + passId, guardToken);
    assertThat(seenByGuard.path("code").isMissingNode() || seenByGuard.path("code").isNull()).isTrue();
    assertThat(post("/v1/gatepasses/verify", Map.of("code", code), guardToken).path("usableNow").asString())
        .isEqualTo("YES");
    assertThat(post("/v1/gatepasses/verify", Map.of("code", wrong(code)), guardToken).path("status").asInt())
        .isEqualTo(404);

    JsonNode first = post("/v1/entries/pass", Map.of("code", code), guardToken);
    assertThat(first.path("status").asString()).isEqualTo("IN");
    assertThat(first.path("visitorName").asString()).isEqualTo("Ravi");
    assertThat(first.path("passId").asString()).isEqualTo(passId.toString());
    assertThat(outbox("security.entry.checked_in", UUID.fromString(first.path("id").asString()))).contains("GUEST");
    JsonNode second = post("/v1/entries/pass", Map.of("qrToken", pass.path("qrToken").asString()), guardToken);
    assertThat(second.path("status").asString()).isEqualTo("IN");
    JsonNode third = post("/v1/entries/pass", Map.of("code", code), guardToken);
    assertThat(third.path("code").asString()).isEqualTo("PASS_EXHAUSTED");
    assertThat(count("select count(*) from gate_pass where id = '" + passId + "' and used_count = 2 and status = 'EXHAUSTED'"))
        .isEqualTo(1);

    // A cancelled pass is refused with its reason.
    acting(RESIDENT);
    JsonNode other = post("/v1/gatepasses", Map.of("flatId", flat, "kind", "CAB", "validTo", validTo.toString()),
        residentToken);
    post("/v1/gatepasses/" + other.path("id").asString() + "/cancel", Map.of(), residentToken);
    acting(GUARD);
    assertThat(post("/v1/entries/pass", Map.of("code", other.path("code").asString()), guardToken).path("code").asString())
        .isEqualTo("PASS_CANCELLED");

    // Residents cannot issue passes for a flat they do not live in.
    acting(RESIDENT);
    JsonNode notMine = post("/v1/gatepasses", Map.of("flatId", UuidV7.next(), "kind", "GUEST",
        "validTo", validTo.toString()), residentToken);
    assertThat(notMine.path("code").asString()).isEqualTo("NOT_YOUR_FLAT");
  }

  @Test
  void householdEventsDriveStaffAndVehicleEntriesAndEndedMembershipsLoseAccess() throws Exception {
    UUID staff = UuidV7.next();
    UUID vehicle = UuidV7.next();
    sendEvent("sos.society.events.v1", society, "society.domesticstaff.registered", Map.of("staffId", staff,
        "name", "Sunita", "kind", "MAID", "flatIds", List.of(flat), "kycStatus", "VERIFIED", "status", "ACTIVE"));
    sendEvent("sos.society.events.v1", society, "society.vehicle.registered", Map.of("vehicleId", vehicle,
        "flatId", flat, "regNo", "KA 01 AB 1234", "kind", "CAR", "rfidTag", "RF-77"));
    await(() -> count("select count(*) from domestic_staff where id = '" + staff + "'") == 1);
    await(() -> count("select count(*) from flat_vehicle where id = '" + vehicle + "'") == 1);

    acting(GUARD);
    JsonNode card = get("/v1/directory/flats/" + flat, guardToken);
    assertThat(card.path("residents").toString()).contains("Asha Rao").doesNotContain(resident.toString());
    JsonNode enrolled = put("/v1/directory/staff/" + staff + "/phone", Map.of("phone", "9123456789"), guardToken);
    assertThat(enrolled.path("phoneMasked").asString()).isEqualTo("91XXXXXX89");

    JsonNode in = post("/v1/staff-attendance/check-in", Map.of("phone", "+91 91234 56789"), guardToken);
    assertThat(in.path("staffName").asString()).isEqualTo("Sunita");
    UUID entryId = UUID.fromString(in.path("entryId").asString());
    assertThat(outbox("security.entry.checked_in", entryId)).contains("STAFF");
    assertThat(post("/v1/staff-attendance/check-in", Map.of("staffId", staff), guardToken).path("code").asString())
        .isEqualTo("STAFF_ALREADY_INSIDE");
    assertThat(post("/v1/staff-attendance/check-out", Map.of("staffId", staff), guardToken).path("outAt").isNull())
        .isFalse();
    assertThat(outbox("security.entry.checked_out", entryId)).isNotEmpty();

    JsonNode car = post("/v1/vehicle-movements", Map.of("regNo", "ka01ab1234", "direction", "IN"), guardToken);
    assertThat(car.path("known").asBoolean()).isTrue();
    assertThat(car.path("flatLabel").asString()).isEqualTo("A-1203");
    JsonNode rfid = post("/v1/vehicle-movements", Map.of("rfidTag", "RF-77", "direction", "OUT"), guardToken);
    assertThat(rfid.path("matchedBy").asString()).isEqualTo("RFID");

    // Blocked staff are turned away.
    sendEvent("sos.society.events.v1", society, "society.domesticstaff.updated", Map.of("staffId", staff,
        "name", "Sunita", "kind", "MAID", "flatIds", List.of(flat), "status", "BLOCKED"));
    await(() -> count("select count(*) from domestic_staff where id = '" + staff + "' and status = 'BLOCKED'") == 1);
    assertThat(post("/v1/staff-attendance/check-in", Map.of("staffId", staff), guardToken).path("code").asString())
        .isEqualTo("STAFF_BLOCKED");

    // Membership ended: the former resident can no longer decide for the flat.
    UUID leaver = UuidV7.next();
    UUID membership = UuidV7.next();
    Map<String, Object> m = Map.of("membershipId", membership, "flatId", flat, "userId", leaver, "kind", "TENANT",
        "isPrimary", false, "residentName", "Tenant");
    sendEvent("sos.society.events.v1", society, "society.membership.created", m);
    await(() -> count("select count(*) from flat_resident where id = '" + membership + "' and ended_at is null") == 1);
    sendEvent("sos.society.events.v1", society, "society.membership.ended", m);
    await(() -> count("select count(*) from flat_resident where id = '" + membership + "' and ended_at is not null") == 1);
    UUID id = UUID.fromString(requestEntry("Friend", null, "GUEST").path("id").asString());
    acting(RESIDENT);
    assertThat(post("/v1/entries/" + id + "/decision", Map.of("decision", "APPROVE"),
        TestJwtIssuer.token(leaver, society, "RESIDENT_TENANT")).path("code").asString()).isEqualTo("NOT_YOUR_FLAT");
  }

  @Test
  void sosAlertsGuardsFromTheRoleCopy() throws Exception {
    sendEvent("sos.identity.events.v1", society, "identity.role.assigned",
        Map.of("assignmentId", UuidV7.next(), "userId", guard, "roleCode", "GUARD", "source", "MANUAL"));
    await(() -> count("select count(*) from staff_role where user_id = '" + guard + "'") == 1);

    acting(RESIDENT);
    JsonNode sos = post("/v1/sos", Map.of("kind", "MEDICAL"), residentToken);
    assertThat(sos.path("state").asString()).isEqualTo("OPEN");
    assertThat(sos.path("flatId").asString()).isEqualTo(flat.toString());
    UUID sosId = UUID.fromString(sos.path("id").asString());
    assertThat(outbox("security.sos.raised", sosId)).contains("MEDICAL");
    assertThat(count("select count(*) from outbox_event where type = 'security.notification.requested' and payload::text like '%"
        + sosId + "%' and payload::text like '%" + guard + "%'")).isEqualTo(1);

    acting(GUARD);
    assertThat(post("/v1/sos/" + sosId + "/resolve", Map.of(), guardToken).path("state").asString()).isEqualTo("RESOLVED");
    JsonNode incident = post("/v1/incidents", Map.of("kind", "TRESPASS", "severity", "HIGH", "locationText", "Gate 2"),
        guardToken);
    assertThat(outbox("security.incident.reported", UUID.fromString(incident.path("id").asString()))).contains("HIGH");
  }

  @Test
  void oneSocietyCannotReadAnothersRows() throws Exception {
    UUID id = UUID.fromString(requestEntry("Ramesh", "9876543210", "GUEST").path("id").asString());

    UUID otherSociety = UuidV7.next();
    UUID otherGuard = UuidV7.next();
    String otherToken = TestJwtIssuer.token(otherGuard, otherSociety, "GUARD");
    acting(GUARD);
    assertThat(get("/v1/entries/" + id, otherToken).path("status").asInt()).isEqualTo(404);
    assertThat(get("/v1/entries", otherToken).path("items").size()).isZero();
    assertThat(get("/v1/entries/pending", otherToken).size()).isZero();
    assertThat(post("/v1/visitors/lookup", Map.of("phone", "9876543210"), otherToken).path("status").asInt()).isEqualTo(404);
    assertThat(post("/v1/visitors/lookup", Map.of("phone", "9876543210"), guardToken).path("name").asString())
        .isEqualTo("Ramesh");
    // A token for another society cannot switch into this one.
    assertThat(http.get().uri("/v1/entries").header("Authorization", "Bearer " + otherToken)
        .header("X-Society-Id", society.toString()).retrieve().toEntity(String.class).getStatusCode().value())
        .isEqualTo(403);

    // Database layer: the app role sees only the society in app.society_ids and cannot write elsewhere.
    try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app", "app")) {
      app.setAutoCommit(false);
      try (Statement s = app.createStatement()) {
        assertThat(count(s, "select count(*) from entry_log where id = '" + id + "'")).isZero();
        s.execute("select set_config('app.society_ids', '{" + otherSociety + "}', true), set_config('app.write_society_id', '"
            + otherSociety + "', true)");
        assertThat(count(s, "select count(*) from entry_log where id = '" + id + "'")).isZero();
        assertThat(count(s, "select count(*) from visitor where society_id = '" + society + "'")).isZero();
        assertThatThrownBy(() -> s.execute("insert into flat_directory (id, society_id, number, label) values ('"
            + UuidV7.next() + "', '" + society + "', '1', 'X-1')")).hasMessageContaining("row-level security");
      }
      app.rollback();
      try (Statement s = app.createStatement()) {
        s.execute("select set_config('app.society_ids', '{" + society + "}', true)");
        assertThat(count(s, "select count(*) from entry_log where id = '" + id + "'")).isEqualTo(1);
      }
      app.rollback();
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  private JsonNode requestEntry(String name, String phone, String purpose) {
    acting(GUARD);
    Map<String, Object> visitor = new java.util.HashMap<>();
    visitor.put("name", name);
    if (phone != null) {
      visitor.put("phone", phone);
    }
    JsonNode r = post("/v1/entries", Map.of("flatId", flat, "visitor", visitor, "purpose", purpose, "company", "Swiggy"),
        guardToken);
    assertThat(r.path("id").isMissingNode()).as(r.toString()).isFalse();
    return r;
  }

  /** identity-service is stubbed: every caller gets these permissions until the next call. */
  private void acting(String... permissions) {
    var keys = redis.keys("perm:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
    givenPermissions(permissions);
  }

  private void sendEvent(String topic, UUID societyId, String type, Map<String, Object> data) throws Exception {
    UUID id = UuidV7.next();
    Map<String, Object> envelope = Map.of("specversion", "1.0", "id", id, "source", "test", "type", type,
        "time", Instant.now().toString(), "subject", "test", "societyid", societyId, "actortype", "SYSTEM", "data", data);
    var record = new ProducerRecord<>(topic, societyId.toString(), json.writeValueAsString(envelope));
    record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
    record.headers().add("ce_id", id.toString().getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get();
  }

  private String outbox(String type, UUID aggregateId) {
    List<String> rows = new ArrayList<>();
    try (Connection c = owner(); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("select payload::text from outbox_event where type = '" + type
            + "' and aggregate_id = '" + aggregateId + "'")) {
      while (rs.next()) {
        rows.add(rs.getString(1));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    assertThat(rows).as("outbox %s for %s", type, aggregateId).hasSize(1);
    return rows.getFirst();
  }

  private JsonNode post(String path, Object body, String token) {
    return json.readTree(http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
        .header("Authorization", "Bearer " + token).retrieve().body(String.class));
  }

  private JsonNode put(String path, Object body, String token) {
    return json.readTree(http.put().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
        .header("Authorization", "Bearer " + token).retrieve().body(String.class));
  }

  private JsonNode get(String path, String token) {
    return json.readTree(http.get().uri(path).header("Authorization", "Bearer " + token).retrieve().body(String.class));
  }

  private static String wrong(String code) {
    return code.equals("000000") ? "000001" : "000000";
  }

  private static Connection owner() throws java.sql.SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static long count(String sql) {
    try (Connection c = owner(); Statement s = c.createStatement()) {
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

  private static void await(BooleanSupplier condition) throws InterruptedException {
    await(condition, Duration.ofSeconds(30));
  }

  private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Condition not met within " + timeout);
  }
}
