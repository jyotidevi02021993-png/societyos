package in.societyos.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import in.societyos.workflow.platform.core.UuidV7;
import in.societyos.workflow.platform.core.tenant.TenantContext;
import in.societyos.workflow.platform.security.IdentityPermissionsClient;
import in.societyos.workflow.platform.test.IntegrationTestBase;
import in.societyos.workflow.platform.test.TestJwtIssuer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * workflow-service end to end: real Postgres (RLS on), Kafka, Redis and db-scheduler. ticket-service
 * and identity-service are simulated by publishing their events onto Kafka.
 */
class WorkflowFlowIntegrationTest extends IntegrationTestBase {

  static final List<String> ADMIN = List.of("workflow:manage", "approval:decide");
  static final List<String> APPROVER = List.of("approval:decide");

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;
  final Map<UUID, List<String>> permissionsOf = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    http = RestClient.builder().baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();
    when(identityPermissions.myPermissions()).thenAnswer(inv -> new IdentityPermissionsClient.Permissions(null,
        permissionsOf.getOrDefault(TenantContext.userId().orElse(null), List.of())));
  }

  class Society {
    final UUID id = UuidV7.next();
    final UUID adminId = user(ADMIN);
    final UUID fmId = user(APPROVER);
    final UUID member1 = user(APPROVER);
    final UUID member2 = user(APPROVER);
    final UUID residentId = user(APPROVER);
    final String admin = TestJwtIssuer.token(adminId, id, "ESTATE_MANAGER");
    final String fm = TestJwtIssuer.token(fmId, id, "FACILITY_MANAGER");
    final String committee1 = TestJwtIssuer.token(member1, id, "RWA_COMMITTEE");
    final String committee2 = TestJwtIssuer.token(member2, id, "RWA_COMMITTEE");
    final String resident = TestJwtIssuer.token(residentId, id, "RESIDENT_OWNER");

    UUID user(List<String> permissions) {
      UUID u = UuidV7.next();
      permissionsOf.put(u, permissions);
      return u;
    }

    /** JOBCARD: above ₹5,000 the FM approves; above ₹10,000 also 2 committee members. */
    void jobCardWorkflow() {
      JsonNode d = post("/v1/definitions", Map.of("kind", "jobcard", "name", "Job card cost", "thresholdPaise", 500_000,
          "steps", List.of(
              Map.of("name", "Facility manager", "approverRole", "FACILITY_MANAGER"),
              Map.of("name", "Committee", "approverRole", "RWA_COMMITTEE", "appliesAbovePaise", 1_000_000,
                  "requiredApprovals", 2))), admin);
      assertThat(d.path("version").asInt()).as(d.toString()).isEqualTo(1);
    }
  }

  @Test
  void jobCardCostNeedsTwoStepApproval() throws Exception {
    Society s = new Society();
    s.jobCardWorkflow();
    send(s.id, "sos.identity.events.v1", "identity.role.assigned", Map.of("assignmentId", UuidV7.next(),
        "userId", s.fmId, "roleCode", "FACILITY_MANAGER", "source", "MANUAL"));
    await("role holder known", () -> count("select count(*) from role_holder where user_id = '" + s.fmId + "'") == 1);

    UUID cheap = UuidV7.next();
    send(s.id, "sos.ticket.events.v1", "ticket.jobcard.completed", Map.of("jobCardId", cheap, "number", "JC-1",
        "labourCostPaise", 10_000, "spareCostPaise", 0, "totalCostPaise", 10_000));
    UUID card = UuidV7.next();
    send(s.id, "sos.ticket.events.v1", "ticket.jobcard.completed", Map.of("jobCardId", card, "number", "JC-2",
        "labourCostPaise", 1_000_000, "spareCostPaise", 500_000, "totalCostPaise", 1_500_000));

    await("approval requested", () -> outbox("workflow.approval.requested", s.id) == 1);
    await("cheap card auto-approved", () -> count("select count(*) from outbox_event where type = 'workflow.instance.approved'"
        + " and payload->'data'->>'subjectId' = '" + cheap + "'") == 1);
    await("FM notified", () -> count("select count(*) from outbox_event where type = 'workflow.notification.requested'"
        + " and payload::text like '%" + s.fmId + "%'") == 1);

    JsonNode instances = get("/v1/instances?subjectType=JOBCARD&subjectId=" + card, s.admin);
    assertThat(instances.size()).as(instances.toString()).isEqualTo(1);
    UUID instanceId = UUID.fromString(instances.get(0).path("id").asString());
    assertThat(instances.get(0).path("status").asString()).isEqualTo("RUNNING");
    assertThat(instances.get(0).path("amountPaise").asLong()).isEqualTo(1_500_000);

    // Step 1: only the facility manager may decide
    assertThat(get("/v1/approvals/inbox", s.resident).size()).isZero();
    JsonNode inbox = get("/v1/approvals/inbox", s.fm);
    assertThat(inbox.size()).isEqualTo(1);
    UUID task1 = UUID.fromString(inbox.get(0).path("id").asString());
    assertThat(decide(task1, "APPROVE", s.resident).path("code").asString()).isEqualTo("NOT_YOUR_APPROVAL");
    assertThat(decide(task1, "APPROVE", s.fm).path("status").asString()).isEqualTo("APPROVED");

    // Step 2: 2 of the committee
    await("step 2 requested", () -> outbox("workflow.approval.requested", s.id) == 2);
    UUID task2 = UUID.fromString(get("/v1/approvals/inbox", s.committee1).get(0).path("id").asString());
    JsonNode one = decide(task2, "APPROVE", s.committee1);
    assertThat(one.path("status").asString()).as(one.toString()).isEqualTo("PENDING");
    assertThat(one.path("approvalsCount").asInt()).isEqualTo(1);
    assertThat(decide(task2, "APPROVE", s.committee1).path("code").asString()).isEqualTo("ALREADY_DECIDED");
    assertThat(decide(task2, "APPROVE", s.committee2).path("status").asString()).isEqualTo("APPROVED");

    JsonNode detail = get("/v1/instances/" + instanceId, s.admin);
    assertThat(detail.path("instance").path("status").asString()).isEqualTo("APPROVED");
    assertThat(detail.path("steps").size()).isEqualTo(2);
    assertThat(detail.path("steps").get(1).path("votes").size()).isEqualTo(2);
    assertThat(count("select count(*) from outbox_event where type = 'workflow.instance.approved'"
        + " and payload->'data'->>'subjectId' = '" + card + "' and payload->'data'->>'subjectType' = 'JOBCARD'"))
        .isEqualTo(1);
  }

  @Test
  void rejectionRejectsTheInstance() throws Exception {
    Society s = new Society();
    s.jobCardWorkflow();
    UUID po = UuidV7.next();
    JsonNode started = post("/v1/instances", Map.of("subjectType", "JOBCARD", "subjectId", po, "subjectRef", "JC-9",
        "amountPaise", 700_000), s.admin);
    assertThat(started.path("status").asString()).as(started.toString()).isEqualTo("RUNNING");
    UUID task = UUID.fromString(get("/v1/approvals/inbox", s.fm).get(0).path("id").asString());
    assertThat(decide(task, "REJECT", s.fm).path("status").asString()).isEqualTo("REJECTED");
    assertThat(get("/v1/instances/" + started.path("id").asString(), s.admin).path("instance").path("status")
        .asString()).isEqualTo("REJECTED");
    assertThat(outbox("workflow.instance.rejected", s.id)).isEqualTo(1);
  }

  @Test
  void breachedComplaintEscalatesThroughDbScheduler() throws Exception {
    Society s = new Society();
    JsonNode policy = post("/v1/sla-policies", Map.of("subjectType", "COMPLAINT", "priority", "P1", "resolveMins", 5,
        "escalationChain", List.of(Map.of("afterMins", 0, "toRole", "FACILITY_MANAGER"),
            Map.of("afterMins", 600, "toRole", "ESTATE_MANAGER"))), s.admin);
    assertThat(policy.path("id").isMissingNode()).as(policy.toString()).isFalse();

    // A P1 complaint raised 10 minutes ago: already past its 5-minute SLA
    UUID complaint = UuidV7.next();
    send(s.id, "sos.ticket.events.v1", "ticket.complaint.created", Map.of("complaintId", complaint, "number", "CMP-7",
        "categoryName", "Lift", "priority", "P1", "raisedBy", UuidV7.next(), "text", "Lift stuck"),
        Instant.now().minus(Duration.ofMinutes(10)));

    await("breached", () -> outbox("workflow.sla.breached", s.id) == 1);
    await("escalated", () -> outbox("workflow.escalated", s.id) == 1);
    assertThat(count("select count(*) from outbox_event where type = 'workflow.escalated' and payload->'data'->>'toRole'"
        + " = 'FACILITY_MANAGER' and payload->'data'->>'level' = '1' and payload->'data'->>'subjectId' = '" + complaint
        + "'")).isEqualTo(1);
    JsonNode sla = get("/v1/sla-timers?subjectType=COMPLAINT&subjectId=" + complaint, s.admin);
    assertThat(sla.path("timers").get(0).path("status").asString()).as(sla.toString()).isEqualTo("FIRED");
    assertThat(sla.path("timers").get(0).path("level").asInt()).isEqualTo(1);
    assertThat(sla.path("escalations").get(0).path("reason").asString()).isEqualTo("SLA");

    send(s.id, "sos.ticket.events.v1", "ticket.complaint.resolved", Map.of("complaintId", complaint, "number", "CMP-7",
        "at", Instant.now().toString()));
    await("stopped", () -> "STOPPED".equals(get("/v1/sla-timers?subjectType=COMPLAINT&subjectId=" + complaint, s.admin)
        .path("timers").get(0).path("status").asString()));
  }

  @Test
  void societiesCannotSeeEachOthersWorkflows() throws Exception {
    Society a = new Society();
    Society b = new Society();
    a.jobCardWorkflow();
    UUID instanceId = UUID.fromString(post("/v1/instances", Map.of("subjectType", "JOBCARD", "subjectId",
        UuidV7.next(), "amountPaise", 700_000), a.admin).path("id").asString());
    UUID task = UUID.fromString(get("/v1/approvals/inbox", a.fm).get(0).path("id").asString());

    assertThat(get("/v1/instances/" + instanceId, b.admin).path("code").asString()).isEqualTo("WORKFLOW_INSTANCE_NOT_FOUND");
    assertThat(get("/v1/instances", b.admin).size()).isZero();
    assertThat(get("/v1/definitions", b.admin).size()).isZero();
    assertThat(get("/v1/approvals/inbox", b.fm).size()).isZero();
    assertThat(decide(task, "APPROVE", b.fm).path("status").asInt()).isEqualTo(404);

    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app", "app")) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("select set_config('app.society_ids', '{" + b.id + "}', true)");
        for (String table : List.of("workflow_instance", "approval_task", "workflow_definition")) {
          try (ResultSet rs = st.executeQuery("select count(*) from " + table + " where society_id = '" + a.id + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).as(table).isZero();
          }
        }
      }
      c.rollback();
    }
  }

  // --- helpers ---------------------------------------------------------------------------

  JsonNode decide(UUID task, String decision, String token) {
    return post("/v1/approvals/" + task + "/decide", Map.of("decision", decision, "comment", "ok"), token);
  }

  void send(UUID societyId, String topic, String type, Map<String, Object> data) throws Exception {
    send(societyId, topic, type, data, Instant.now());
  }

  void send(UUID societyId, String topic, String type, Map<String, Object> data, Instant time) throws Exception {
    UUID eventId = UuidV7.next();
    Map<String, Object> envelope = new HashMap<>();
    envelope.put("specversion", "1.0");
    envelope.put("id", eventId);
    envelope.put("source", "test");
    envelope.put("type", type);
    envelope.put("time", time.toString());
    envelope.put("subject", "test");
    envelope.put("societyid", societyId);
    envelope.put("actortype", "SYSTEM");
    envelope.put("data", data);
    var record = new ProducerRecord<>(topic, societyId.toString(), json.writeValueAsString(envelope));
    record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
    record.headers().add("ce_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get();
  }

  static void await(String what, Callable<Boolean> condition) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      if (Boolean.TRUE.equals(condition.call())) {
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Timed out waiting for: " + what);
  }

  JsonNode get(String path, String token) {
    return http.get().uri(path).header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
  }

  JsonNode post(String path, Object body, String token) {
    return http.post().uri(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
  }

  long outbox(String type, UUID societyId) throws Exception {
    return count("select count(*) from outbox_event where type = '" + type + "' and society_id = '" + societyId + "'");
  }

  static long count(String sql) throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
