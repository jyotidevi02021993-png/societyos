package in.societyos.utility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.utility.platform.core.UuidV7;
import in.societyos.utility.platform.test.IntegrationTestBase;
import in.societyos.utility.platform.test.TestJwtIssuer;
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

/** utility-service end to end: real Postgres (RLS on, partitioned readings), Kafka and Redis. */
class UtilityFlowIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper json;

  RestClient http;
  UUID society;
  String token;

  @BeforeEach
  void setUp() {
    http = RestClient.builder().baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();
    society = UuidV7.next();
    token = TestJwtIssuer.token(UuidV7.next(), society, "FACILITY_MANAGER");
    givenPermissions("checklist:manage", "checklist:execute", "reading:record", "signoff:daily", "dashboard:view");
  }

  @Test
  void readingOutsideThresholdRaisesAnomalyEvent() {
    JsonNode ph = post("/v1/meters", Map.of("code", "stp-ph", "name", "STP outlet pH", "system", "STP",
        "metric", "PH", "unit", "pH", "mode", "INSTANT", "expectedMin", 6.5, "expectedMax", 8.5));
    String meterId = ph.path("id").asString();

    JsonNode normal = post("/v1/readings", Map.of("meterId", meterId, "value", 7.2));
    assertThat(normal.path("anomaly").asBoolean()).isFalse();
    JsonNode high = post("/v1/readings", Map.of("meterId", meterId, "value", 9.4));
    assertThat(high.path("anomaly").asBoolean()).isTrue();
    assertThat(high.path("anomalyReason").asString()).isEqualTo("ABOVE_MAX");

    assertThat(outboxCount("utility.reading.recorded")).isEqualTo(2);
    assertThat(outboxCount("utility.reading.anomaly")).isEqualTo(1);
    assertThat(countRows("select count(*) from outbox_event where type = 'utility.reading.anomaly' and payload->'data'->>'readingId' = '"
        + high.path("id").asString() + "' and (payload->'data'->>'expectedMax')::numeric = 8.5")).isEqualTo(1);
    assertThat(outboxCount("utility.notification.requested")).isEqualTo(1);

    // cumulative water meter: consumption spike and rollback
    JsonNode water = post("/v1/meters", Map.of("code", "wm-main", "name", "Main inlet", "system", "WATER",
        "metric", "FLOW_KL", "unit", "KL", "mode", "CUMULATIVE", "maxDelta", 400));
    String wm = water.path("id").asString();
    JsonNode round = postArray("/v1/readings/batch", Map.of("readings", List.of(
        Map.of("meterId", wm, "value", 1000, "at", Instant.now().minusSeconds(7200).toString()),
        Map.of("meterId", wm, "value", 1150, "at", Instant.now().minusSeconds(3600).toString()),
        Map.of("meterId", wm, "value", 1900))));
    assertThat(round.size()).isEqualTo(3);
    assertThat(round.get(1).path("delta").decimalValue()).isEqualByComparingTo("150");
    assertThat(round.get(2).path("anomalyReason").asString()).isEqualTo("CONSUMPTION_SPIKE");

    JsonNode anomalies = get("/v1/readings?anomalyOnly=true");
    assertThat(anomalies.size()).isEqualTo(2);
  }

  @Test
  void checklistRunFailsItemsAndManagerSignsOff() {
    JsonNode template = post("/v1/checklist-templates", Map.of("code", "dg-daily", "name", "DG daily check",
        "system", "DG", "frequency", "DAILY", "items", List.of(
            Map.of("code", "OIL", "label", "Oil pressure", "type", "NUMBER", "required", true, "min", 3, "max", 6, "unit", "bar"),
            Map.of("code", "BATT", "label", "Battery OK", "type", "CHECK", "required", true))));
    String templateId = template.path("id").asString();

    // sign-off blocked while the daily checklist is pending
    JsonNode blocked = post("/v1/signoffs", Map.of("remarks", "x"));
    assertThat(blocked.path("code").asString()).isEqualTo("SIGNOFF_PENDING_CHECKLISTS");

    JsonNode run = post("/v1/checklist-runs", Map.of("templateId", templateId, "shift", "MORNING"));
    String runId = run.path("run").path("id").asString();
    assertThat(post("/v1/checklist-runs", Map.of("templateId", templateId, "shift", "MORNING"))
        .path("run").path("id").asString()).as("same run for same day and shift").isEqualTo(runId);

    JsonNode done = post("/v1/checklist-runs/" + runId + "/submit", Map.of("answers", List.of(
        Map.of("code", "OIL", "value", 2.1), Map.of("code", "BATT", "result", "OK"))));
    assertThat(done.path("run").path("status").asString()).isEqualTo("COMPLETED");
    assertThat(done.path("run").path("failedCount").asInt()).isEqualTo(1);
    assertThat(outboxCount("utility.checklist.completed")).isEqualTo(1);
    assertThat(outboxCount("utility.checklist.item_failed")).isEqualTo(1);

    JsonNode summary = get("/v1/daily-summary");
    assertThat(summary.path("runsCompleted").asInt()).isEqualTo(1);
    assertThat(summary.path("failedItems").asInt()).isEqualTo(1);
    assertThat(summary.path("pendingChecklists").size()).isZero();

    JsonNode signed = post("/v1/signoffs", Map.of("remarks", "DG oil pressure low, ticket raised"));
    assertThat(signed.path("summary").path("failedItems").asInt()).isEqualTo(1);
    assertThat(post("/v1/signoffs", Map.of()).path("code").asString()).isEqualTo("ALREADY_SIGNED_OFF");
    assertThat(outboxCount("utility.signoff.completed")).isEqualTo(1);
  }

  @Test
  void consumesAssetAndSocietyEvents() throws Exception {
    UUID assetId = UuidV7.next();
    UUID locationId = UuidV7.next();
    send("sos.society.events.v1", "society.location.created",
        Map.of("locationId", locationId, "kind", "PLANT_ROOM", "name", "DG room"));
    send("sos.asset.events.v1", "asset.asset.created", Map.of("assetId", assetId, "code", "AST-2026-000001",
        "name", "DG set", "categoryGroup", "ELECTRICAL", "locationId", locationId, "status", "WORKING"));
    await(() -> countRows("select count(*) from asset_ref where id = '" + assetId + "'") == 1
        && countRows("select count(*) from location_ref where id = '" + locationId + "'") == 1);

    // a meter on the asset inherits its location from the read model
    JsonNode meter = post("/v1/meters", Map.of("code", "dg-hours", "name", "DG hour meter", "system", "DG",
        "assetId", assetId, "metric", "RUNNING_HOURS", "unit", "h", "mode", "CUMULATIVE"));
    assertThat(meter.path("locationId").asString()).isEqualTo(locationId.toString());
  }

  @Test
  void oneSocietyCannotReadAnothersRows() throws Exception {
    JsonNode meter = post("/v1/meters", Map.of("code", "tank-1", "name", "OHT 1 level", "system", "TANK",
        "metric", "LEVEL_PCT", "unit", "%", "mode", "INSTANT"));
    JsonNode reading = post("/v1/readings", Map.of("meterId", meter.path("id").asString(), "value", 80));

    UUID other = UuidV7.next();
    String otherToken = TestJwtIssuer.token(UuidV7.next(), other, "FACILITY_MANAGER");
    JsonNode notFound = json.readTree(http.get().uri("/v1/meters/" + meter.path("id").asString())
        .header("Authorization", "Bearer " + otherToken).retrieve().body(String.class));
    assertThat(notFound.path("code").asString()).isEqualTo("METER_NOT_FOUND");
    JsonNode otherReadings = json.readTree(http.get().uri("/v1/readings")
        .header("Authorization", "Bearer " + otherToken).retrieve().body(String.class));
    assertThat(otherReadings.toString()).doesNotContain(reading.path("id").asString());

    try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app", "app")) {
      app.setAutoCommit(false);
      try (Statement s = app.createStatement()) {
        s.execute("select set_config('app.society_ids', '{" + other + "}', true), "
            + "set_config('app.write_society_id', '" + other + "', true)");
        assertThat(count(s, "select count(*) from reading where id = '" + reading.path("id").asString() + "'")).isZero();
        assertThat(count(s, "select count(*) from meter where society_id = '" + society + "'")).isZero();
        assertThatThrownBy(() -> s.execute("insert into meter (id, society_id, code, name, system, metric, unit, reading_mode) "
            + "values ('" + UuidV7.next() + "', '" + society + "', 'X', 'x', 'TANK', 'L', '%', 'INSTANT')"))
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

  private JsonNode postArray(String path, Object body) {
    return post(path, body);
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
