package in.societyos.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.test.IntegrationTestBase;
import in.societyos.asset.platform.test.TestJwtIssuer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
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

/** asset-service end to end: real Postgres (RLS on), Kafka and Redis. */
class AssetFlowIntegrationTest extends IntegrationTestBase {

  static final String[] PERMS = {"asset:manage", "asset:view", "pm:manage", "pm:execute", "breakdown:report"};

  @LocalServerPort int port;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper json;

  RestClient http;
  UUID user;
  UUID society;
  String token;
  LocalDate today;

  @BeforeEach
  void setUp() {
    http = RestClient.builder().baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();
    user = UuidV7.next();
    society = UuidV7.next();
    token = TestJwtIssuer.token(user, society, "ESTATE_MANAGER");
    today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    givenPermissions(PERMS);
  }

  @Test
  void assetCreatedThenPmTaskGeneratedThenExecuted() {
    JsonNode asset = post("/v1/assets", Map.of("name", "Borewell pump 1", "category", "WATER", "make", "Kirloskar",
        "serialNo", "KP-7781", "pmFrequency", "DAILY"));
    assertThat(asset.path("assetCode").asString()).startsWith("AST-");
    assertThat(asset.path("qrToken").asString()).hasSize(22);
    String assetId = asset.path("id").asString();

    // a second, explicit plan with a checklist
    JsonNode plan = post("/v1/pm-plans", Map.of("assetId", assetId, "name", "Weekly pump check", "frequency", "WEEKLY",
        "anchorOn", today.toString(), "checklist", List.of(
            Map.of("code", "SEAL", "label", "Gland seal leak", "required", true),
            Map.of("code", "AMPS", "label", "Current within rating", "required", true))));
    assertThat(plan.path("nextDueOn").asString()).isEqualTo(today.toString());
    assertThat(get("/v1/pm-plans?assetId=" + assetId).size()).isEqualTo(2);

    // the nightly job (run on demand): one task per plan, idempotent
    JsonNode run = post("/v1/pm-tasks/generate?date=" + today, Map.of());
    assertThat(run.path("pmTasksGenerated").asInt()).isEqualTo(2);
    assertThat(post("/v1/pm-tasks/generate?date=" + today, Map.of()).path("pmTasksGenerated").asInt()).isZero();
    assertThat(outboxCount("asset.pmtask.due")).isEqualTo(2);
    assertThat(outboxCount("asset.notification.requested")).isGreaterThanOrEqualTo(2);

    JsonNode tasks = get("/v1/pm-tasks?assetId=" + assetId + "&status=DUE");
    assertThat(tasks.size()).isEqualTo(2);
    JsonNode weekly = null;
    for (JsonNode t : tasks) {
      if (t.path("pmPlanId").asString().equals(plan.path("id").asString())) {
        weekly = t;
      }
    }
    assertThat(weekly).isNotNull();
    assertThat(weekly.path("number").asString()).startsWith("PM-");
    assertThat(weekly.path("checklist").size()).isEqualTo(2);
    String taskId = weekly.path("id").asString();

    // missing a required item is rejected
    JsonNode bad = post("/v1/pm-tasks/" + taskId + "/complete",
        Map.of("results", List.of(Map.of("code", "SEAL", "outcome", "OK"))));
    assertThat(bad.path("code").asString()).isEqualTo("INVALID_CHECKLIST_RESULT");

    assertThat(post("/v1/pm-tasks/" + taskId + "/start", Map.of()).path("status").asString()).isEqualTo("IN_PROGRESS");
    JsonNode done = post("/v1/pm-tasks/" + taskId + "/complete", Map.of(
        "results", List.of(Map.of("code", "SEAL", "outcome", "OK"),
            Map.of("code", "AMPS", "outcome", "NOT_OK", "note", "Drawing 9.2 A against 7.5 A")),
        "costPaise", 25000, "raiseBreakdown", true));
    assertThat(done.path("status").asString()).isEqualTo("DONE");
    assertThat(done.path("okCount").asInt()).isEqualTo(1);
    assertThat(done.path("failedCount").asInt()).isEqualTo(1);
    assertThat(done.path("results").get(1).path("label").asString()).isEqualTo("Current within rating");

    // failed check handed to ticket-service as a breakdown; history and cost recorded
    JsonNode after = get("/v1/assets/" + assetId);
    assertThat(after.path("status").asString()).isEqualTo("BREAKDOWN");
    assertThat(after.path("totalMaintenanceCostPaise").asLong()).isEqualTo(25000);
    assertThat(outboxCount("asset.pmtask.completed")).isEqualTo(1);
    assertThat(outboxCount("asset.breakdown.reported")).isEqualTo(1);
    assertThat(outboxCount("asset.asset.status_changed")).isEqualTo(1);
    JsonNode history = get("/v1/assets/" + assetId + "/history");
    assertThat(history.toString()).contains("PM_DONE").contains("BREAKDOWN").contains("CREATED");

    // QR scan returns the full profile
    JsonNode profile = get("/v1/assets/qr/" + asset.path("qrToken").asString());
    assertThat(profile.path("asset").path("id").asString()).isEqualTo(assetId);
    assertThat(profile.path("pmPlans").size()).isEqualTo(2);
    assertThat(profile.path("openPmTasks").size()).isEqualTo(1);
  }

  @Test
  void ticketEventsDriveStatusHistoryAndPmCompletion() throws Exception {
    JsonNode asset = post("/v1/assets", Map.of("name", "Lift A1", "category", "LIFT", "pmFrequency", "MONTHLY"));
    UUID assetId = UUID.fromString(asset.path("id").asString());
    post("/v1/pm-tasks/generate?date=" + today, Map.of());
    UUID pmTaskId = UUID.fromString(get("/v1/pm-tasks?assetId=" + assetId).get(0).path("id").asString());

    UUID breakdownId = UuidV7.next();
    send("sos.ticket.events.v1", "ticket.breakdown.reported",
        Map.of("breakdownId", breakdownId, "number", "BRK-2026-000001", "assetId", assetId, "priority", "P1"));
    await(() -> "BREAKDOWN".equals(get("/v1/assets/" + assetId).path("status").asString()));

    UUID repairJc = UuidV7.next();
    send("sos.ticket.events.v1", "ticket.jobcard.created", Map.of("jobCardId", repairJc, "number", "JC-2026-000001",
        "sourceType", "BREAKDOWN", "sourceId", breakdownId, "assetId", assetId, "priority", "P1"));
    await(() -> "UNDER_REPAIR".equals(get("/v1/assets/" + assetId).path("status").asString()));

    Map<String, Object> closed = new HashMap<>(Map.of("jobCardId", repairJc, "number", "JC-2026-000001",
        "assetId", assetId, "sourceType", "BREAKDOWN", "sourceId", breakdownId, "labourCostPaise", 150000,
        "spares", List.of(Map.of("spareId", UuidV7.next(), "qty", 2, "unitCostPaise", 45000)),
        "rootCause", "Door sensor failed", "closedAt", Instant.now().toString()));
    send("sos.ticket.events.v1", "ticket.jobcard.closed", closed);
    await(() -> "WORKING".equals(get("/v1/assets/" + assetId).path("status").asString()));
    JsonNode a = get("/v1/assets/" + assetId);
    assertThat(a.path("totalMaintenanceCostPaise").asLong()).isEqualTo(240000);
    assertThat(a.path("breakdownCount").asInt()).isEqualTo(1);
    assertThat(get("/v1/assets/" + assetId + "/history").toString()).contains("REPAIR").contains("Door sensor failed");

    // the PM job card closes the PM task
    UUID pmJc = UuidV7.next();
    send("sos.ticket.events.v1", "ticket.jobcard.created", Map.of("jobCardId", pmJc, "number", "JC-2026-000002",
        "sourceType", "PM", "sourceId", pmTaskId, "assetId", assetId, "priority", "P3"));
    send("sos.ticket.events.v1", "ticket.jobcard.closed", Map.of("jobCardId", pmJc, "number", "JC-2026-000002",
        "assetId", assetId, "sourceType", "PM", "sourceId", pmTaskId, "labourCostPaise", 0,
        "spares", List.of(), "closedAt", Instant.now().toString()));
    await(() -> "DONE".equals(get("/v1/pm-tasks/" + pmTaskId).path("status").asString()));
    assertThat(get("/v1/pm-tasks/" + pmTaskId).path("jobCardId").asString()).isEqualTo(pmJc.toString());
  }

  @Test
  void usageReadingsRaiseUsageBasedPm() throws Exception {
    JsonNode dg = post("/v1/assets", Map.of("name", "DG set 125 kVA", "category", "ELECTRICAL"));
    UUID dgId = UUID.fromString(dg.path("id").asString());
    post("/v1/pm-plans", Map.of("assetId", dgId, "name", "DG 250 h service", "frequency", "USAGE_BASED",
        "usageMetric", "RUNNING_HOURS", "usageInterval", 250));

    send("sos.utility.events.v1", "utility.reading.recorded", Map.of("readingId", UuidV7.next(), "assetId", dgId,
        "meterId", UuidV7.next(), "metric", "RUNNING_HOURS", "value", 120, "unit", "h", "at", Instant.now().toString()));
    send("sos.utility.events.v1", "utility.reading.recorded", Map.of("readingId", UuidV7.next(), "assetId", dgId,
        "meterId", UuidV7.next(), "metric", "RUNNING_HOURS", "value", 262.5, "unit", "h", "at", Instant.now().toString()));
    await(() -> get("/v1/pm-tasks?assetId=" + dgId).size() == 1);
    assertThat(get("/v1/pm-tasks?assetId=" + dgId).get(0).path("trigger").asString()).isEqualTo("USAGE");
  }

  @Test
  void consumesSocietyEventsForLocationsAndTimeZones() throws Exception {
    UUID locationId = UuidV7.next();
    send("sos.society.events.v1", "society.created",
        Map.of("societyId", society, "name", "Green Meadows", "city", "Pune", "state", "MH", "timezone", "Asia/Kolkata"));
    send("sos.society.events.v1", "society.location.created",
        Map.of("locationId", locationId, "kind", "PLANT_ROOM", "name", "STP plant room", "towerId", UuidV7.next()));
    await(() -> countRows("select count(*) from location_ref where id = '" + locationId + "'") == 1);
    assertThat(countRows("select count(*) from society_ref where society_id = '" + society + "'")).isEqualTo(1);

    JsonNode stp = post("/v1/assets", Map.of("name", "STP blower", "category", "WATER", "locationId", locationId,
        "warrantyUntil", today.plusDays(30).toString()));
    assertThat(stp.path("locationName").asString()).isEqualTo("STP plant room");

    // the warranty on the asset produces a 30-day expiry alert once
    assertThat(post("/v1/pm-tasks/generate?date=" + today, Map.of()).path("expiryAlerts").asInt()).isEqualTo(1);
    assertThat(post("/v1/pm-tasks/generate?date=" + today, Map.of()).path("expiryAlerts").asInt()).isZero();
    assertThat(outboxCount("asset.warranty.expiring")).isEqualTo(1);
    assertThat(countRows("select count(*) from outbox_event where society_id = '" + society
        + "' and type = 'asset.notification.requested' and payload::text like '%asset.warranty.expiring%'")).isEqualTo(1);
  }

  @Test
  void oneSocietyCannotReadAnothersRows() throws Exception {
    JsonNode mine = post("/v1/assets", Map.of("name", "Fire pump", "category", "FIRE_SAFETY"));
    String assetId = mine.path("id").asString();

    UUID otherSociety = UuidV7.next();
    String otherToken = TestJwtIssuer.token(UuidV7.next(), otherSociety, "ESTATE_MANAGER");
    JsonNode notFound = json.readTree(http.get().uri("/v1/assets/" + assetId)
        .header("Authorization", "Bearer " + otherToken).retrieve().body(String.class));
    assertThat(notFound.path("code").asString()).isEqualTo("ASSET_NOT_FOUND");
    JsonNode list = json.readTree(http.get().uri("/v1/assets").header("Authorization", "Bearer " + otherToken)
        .retrieve().body(String.class));
    assertThat(list.toString()).doesNotContain(assetId);
    JsonNode qr = json.readTree(http.get().uri("/v1/assets/qr/" + mine.path("qrToken").asString())
        .header("Authorization", "Bearer " + otherToken).retrieve().body(String.class));
    assertThat(qr.path("code").asString()).isEqualTo("ASSET_NOT_FOUND");

    // and directly in the database, as the application role
    try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app", "app")) {
      app.setAutoCommit(false);
      try (Statement s = app.createStatement()) {
        assertThat(count(s, "select count(*) from asset where id = '" + assetId + "'")).isZero();
        s.execute("select set_config('app.society_ids', '{" + otherSociety + "}', true), "
            + "set_config('app.write_society_id', '" + otherSociety + "', true)");
        assertThat(count(s, "select count(*) from asset where id = '" + assetId + "'")).isZero();
        assertThat(count(s, "select count(*) from asset_history where asset_id = '" + assetId + "'")).isZero();
        assertThatThrownBy(() -> s.execute("insert into pm_plan (id, society_id, asset_id, name, frequency, anchor_on) "
            + "values ('" + UuidV7.next() + "', '" + society + "', '" + assetId + "', 'x', 'DAILY', current_date)"))
            .hasMessageContaining("row-level security");
      }
      app.rollback();
    }
  }

  // --- helpers ---------------------------------------------------------------------------

  private JsonNode post(String path, Object body) {
    return json.readTree(http.post().uri(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
  }

  private JsonNode get(String path) {
    return json.readTree(http.get().uri(path).header("Authorization", "Bearer " + token).retrieve().body(String.class));
  }

  private void send(String topic, String type, Map<String, Object> data) throws Exception {
    UUID id = UuidV7.next();
    Map<String, Object> envelope = Map.of("specversion", "1.0", "id", id, "source", "test", "type", type,
        "time", Instant.now().toString(), "subject", "test", "societyid", society, "actortype", "SYSTEM", "data", data);
    var record = new ProducerRecord<>(topic, society.toString(), json.writeValueAsString(envelope));
    record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
    record.headers().add("ce_id", id.toString().getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get();
  }

  private long outboxCount(String type) {
    return countRows("select count(*) from outbox_event where society_id = '" + society + "' and type = '" + type + "'");
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
    Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Condition not met within 45 s");
  }
}
