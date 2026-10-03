package in.societyos.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import in.societyos.ticket.platform.core.UuidV7;
import in.societyos.ticket.platform.core.tenant.TenantContext;
import in.societyos.ticket.platform.security.IdentityPermissionsClient;
import in.societyos.ticket.platform.test.IntegrationTestBase;
import in.societyos.ticket.platform.test.TestJwtIssuer;
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
 * ticket-service end to end: real Postgres (RLS on), Kafka and Redis. Other services are simulated
 * by publishing their events (society, identity, inventory, workflow) onto Kafka.
 */
class TicketFlowIntegrationTest extends IntegrationTestBase {

  static final List<String> MANAGER = List.of("complaint:view", "complaint:manage", "breakdown:report",
      "jobcard:view", "jobcard:assign", "jobcard:approve", "jobcard:close");
  static final List<String> TECHNICIAN = List.of("jobcard:work", "spare:issue-request", "breakdown:report");
  static final List<String> RESIDENT = List.of("complaint:create");

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

  /** One society with a manager, a technician and a resident living in flat A-1203. */
  class Society {
    final UUID id = UuidV7.next();
    final UUID flat = UuidV7.next();
    final UUID otherFlat = UuidV7.next();
    final UUID managerId = user(MANAGER);
    final UUID techId = user(TECHNICIAN);
    final UUID residentId = user(RESIDENT);
    final String manager = TestJwtIssuer.token(managerId, id, "ESTATE_MANAGER");
    final String tech = TestJwtIssuer.token(techId, id, "TECHNICIAN");
    final String resident = TestJwtIssuer.token(residentId, id, "RESIDENT_OWNER");

    Society() throws Exception {
      send("sos.society.events.v1", id, "society.flat.created", Map.of("flatId", flat, "towerName", "Tower A",
          "label", "A-1203", "number", "1203", "status", "OCCUPIED"));
      send("sos.society.events.v1", id, "society.flat.created", Map.of("flatId", otherFlat, "towerName", "Tower A",
          "label", "A-101", "number", "101", "status", "OCCUPIED"));
      send("sos.society.events.v1", id, "society.membership.created", Map.of("membershipId", UuidV7.next(),
          "flatId", flat, "userId", residentId, "residentId", UuidV7.next(), "residentName", "Asha",
          "kind", "OWNER", "isPrimary", true));
      await("resident's flat known", () -> count("select count(*) from flat_member where user_id = '" + residentId + "'") == 1);
    }

    UUID user(List<String> permissions) {
      UUID u = UuidV7.next();
      permissionsOf.put(u, permissions);
      return u;
    }
  }

  @Test
  void complaintToJobCardToClose() throws Exception {
    Society s = new Society();
    JsonNode category = post("/v1/categories", Map.of("name", "Plumbing", "department", "Maintenance",
        "defaultPriority", "P3"), s.manager);
    assertThat(category.path("name").asString()).as(category.toString()).isEqualTo("Plumbing");
    assertThat(post("/v1/categories", Map.of("name", "plumbing"), s.manager).path("code").asString())
        .isEqualTo("CATEGORY_EXISTS");

    // Resident raises a complaint (flat defaults to their only flat); phone numbers never reach events
    UUID photo = UuidV7.next();
    JsonNode complaint = post("/v1/complaints", Map.of("categoryId", id(category),
        "text", "Kitchen tap leaking badly, call 98765 43210", "mediaIds", List.of(photo)), s.resident);
    UUID complaintId = id(complaint);
    assertThat(complaint.path("number").asString()).startsWith("CMP-");
    assertThat(complaint.path("flatId").asString()).isEqualTo(s.flat.toString());
    assertThat(complaint.path("status").asString()).isEqualTo("OPEN");
    assertThat(complaint.path("slaDueAt").isMissingNode()).isFalse();
    assertThat(outbox("ticket.complaint.created", s.id)).isEqualTo(1);
    assertThat(count("select count(*) from outbox_event where payload::text like '%98765%'")).isZero();
    assertThat(post("/v1/complaints", Map.of("flatId", s.otherFlat, "text", "Not mine"), s.resident)
        .path("code").asString()).isEqualTo("NOT_YOUR_FLAT");
    assertThat(get("/v1/complaints/" + complaintId, s.resident).path("mediaIds").get(0).asString())
        .isEqualTo(photo.toString());

    // Manager triages into a job card assigned to the technician
    JsonNode card = post("/v1/complaints/" + complaintId + "/jobcard", Map.of("assigneeUserId", s.techId), s.manager);
    UUID cardId = id(card);
    assertThat(card.path("number").asString()).startsWith("JC-");
    assertThat(card.path("status").asString()).isEqualTo("ASSIGNED");
    assertThat(get("/v1/complaints/" + complaintId, s.resident).path("complaint").path("status").asString())
        .isEqualTo("IN_PROGRESS");
    assertThat(outbox("ticket.jobcard.created", s.id)).isEqualTo(1);
    assertThat(outbox("ticket.jobcard.assigned", s.id)).isEqualTo(1);
    assertThat(count("select count(*) from outbox_event where type = 'ticket.notification.requested' and payload::text like '%"
        + s.techId + "%' and payload::text like '%jobcard.assigned%'")).isEqualTo(1);
    assertThat(get("/v1/my-tasks", s.tech).size()).isEqualTo(1);

    // Technician works: start, photos, work log, spare request (issued by inventory), complete
    assertThat(transition(cardId, Map.of("action", "START"), s.tech).path("status").asString()).isEqualTo("IN_PROGRESS");
    post("/v1/jobcards/" + cardId + "/evidence", Map.of("stage", "BEFORE", "mediaId", UuidV7.next()), s.tech);
    post("/v1/jobcards/" + cardId + "/work-logs", Map.of("note", "Replaced cartridge", "minutes", 45,
        "costPaise", 30_000), s.tech);
    UUID spareId = UuidV7.next();
    JsonNode spare = post("/v1/jobcards/" + cardId + "/spares", Map.of("spareId", spareId, "qty", 2), s.tech);
    assertThat(spare.path("status").asString()).isEqualTo("REQUESTED");
    send("sos.inventory.events.v1", s.id, "inventory.spare.issued", Map.of("issueId", UuidV7.next(),
        "spareId", spareId, "storeId", UuidV7.next(), "qty", 2, "unitCostPaise", 12_500, "jobCardId", cardId));
    await("spare issued", () -> get("/v1/jobcards/" + cardId, s.tech).path("jobCard").path("spareCostPaise").asLong() == 25_000);

    JsonNode noPhoto = transition(cardId, Map.of("action", "COMPLETE", "workDone", "Cartridge replaced",
        "rootCause", "Worn cartridge"), s.tech);
    assertThat(noPhoto.path("code").asString()).isEqualTo("EVIDENCE_REQUIRED");
    post("/v1/jobcards/" + cardId + "/evidence", Map.of("stage", "AFTER", "mediaId", UuidV7.next()), s.tech);
    JsonNode completed = transition(cardId, Map.of("action", "COMPLETE", "workDone", "Cartridge replaced",
        "rootCause", "Worn cartridge"), s.tech);
    assertThat(completed.path("status").asString()).as(completed.toString()).isEqualTo("COMPLETED");
    assertThat(completed.path("totalCostPaise").asLong()).isEqualTo(55_000);
    assertThat(completed.path("approvalStatus").asString()).isEqualTo("NOT_REQUIRED");
    assertThat(transition(cardId, Map.of("action", "VERIFY"), s.tech).path("code").asString()).isEqualTo("NOT_ALLOWED");

    // Supervisor verifies → complaint resolved; resident accepts and rates → card closed and locked
    assertThat(transition(cardId, Map.of("action", "VERIFY"), s.manager).path("status").asString()).isEqualTo("VERIFIED");
    assertThat(get("/v1/complaints/" + complaintId, s.resident).path("complaint").path("status").asString())
        .isEqualTo("RESOLVED");
    assertThat(outbox("ticket.complaint.resolved", s.id)).isEqualTo(1);
    JsonNode closed = post("/v1/complaints/" + complaintId + "/feedback", Map.of("accepted", true, "rating", 5,
        "comment", "Great"), s.resident);
    assertThat(closed.path("status").asString()).as(closed.toString()).isEqualTo("CLOSED");
    assertThat(closed.path("rating").asInt()).isEqualTo(5);

    JsonNode detail = get("/v1/jobcards/" + cardId, s.manager);
    assertThat(detail.path("jobCard").path("status").asString()).isEqualTo("CLOSED");
    assertThat(detail.path("jobCard").path("locked").asBoolean()).isTrue();
    assertThat(detail.path("jobCard").path("residentConfirmed").asBoolean()).isTrue();
    assertThat(detail.path("history").size()).isGreaterThanOrEqualTo(5);
    assertThat(outbox("ticket.jobcard.closed", s.id)).isEqualTo(1);
    assertThat(count("select count(*) from outbox_event where type = 'ticket.jobcard.closed' and payload->'data'->'spares'->0->>'unitCostPaise' = '12500'"
        + " and payload->'data'->>'rootCause' = 'Worn cartridge'")).isEqualTo(1);

    // Locked: nothing changes any more
    JsonNode locked = post("/v1/jobcards/" + cardId + "/work-logs", Map.of("note", "More", "minutes", 5, "costPaise", 0), s.tech);
    assertThat(locked.path("code").asString()).isEqualTo("JOB_CARD_LOCKED");
    assertThat(transition(cardId, Map.of("action", "REOPEN"), s.manager).path("code").asString()).isEqualTo("JOB_CARD_LOCKED");

    // Reopened within the window: a fresh job card for the same technician
    JsonNode reopened = post("/v1/complaints/" + complaintId + "/reopen", Map.of("reason", "Dripping again"), s.resident);
    assertThat(reopened.path("status").asString()).as(reopened.toString()).isEqualTo("IN_PROGRESS");
    assertThat(reopened.path("jobCardId").asString()).isNotEqualTo(cardId.toString());
    assertThat(outbox("ticket.complaint.reopened", s.id)).isEqualTo(1);
    assertThat(outbox("ticket.jobcard.created", s.id)).isEqualTo(2);
  }

  @Test
  void breakdownGetsAJobCardAndResolvesOnClose() throws Exception {
    Society s = new Society();
    UUID assetId = UuidV7.next();
    send("sos.asset.events.v1", s.id, "asset.asset.created", Map.of("assetId", assetId, "code", "DG-01",
        "name", "DG set 1", "categoryGroup", "POWER", "status", "WORKING"));
    await("asset known", () -> count("select count(*) from asset_summary where id = '" + assetId + "'") == 1);

    JsonNode b = post("/v1/breakdowns", Map.of("assetId", assetId, "fault", "DG not starting", "priority", "P1"), s.tech);
    assertThat(b.path("status").asString()).as(b.toString()).isEqualTo("REPORTED");
    assertThat(b.path("number").asString()).startsWith("BRK-");
    UUID cardId = UUID.fromString(b.path("jobCardId").asString());
    assertThat(outbox("ticket.breakdown.reported", s.id)).isEqualTo(1);

    put("/v1/jobcards/" + cardId + "/assignment", Map.of("assigneeUserId", s.techId), s.manager);
    transition(cardId, Map.of("action", "START"), s.tech);
    assertThat(get("/v1/breakdowns/" + id(b), s.manager).path("breakdown").path("status").asString()).isEqualTo("IN_REPAIR");
    post("/v1/jobcards/" + cardId + "/evidence", Map.of("stage", "AFTER", "mediaId", UuidV7.next()), s.tech);
    transition(cardId, Map.of("action", "COMPLETE", "workDone", "Battery replaced", "rootCause", "Dead battery"), s.tech);
    transition(cardId, Map.of("action", "VERIFY"), s.manager);
    assertThat(transition(cardId, Map.of("action", "CLOSE"), s.manager).path("status").asString()).isEqualTo("CLOSED");
    JsonNode resolved = get("/v1/breakdowns/" + id(b), s.manager).path("breakdown");
    assertThat(resolved.path("status").asString()).isEqualTo("RESOLVED");
    assertThat(resolved.path("downtimeMins").isMissingNode()).isFalse();
    assertThat(get("/v1/jobcards/" + cardId, s.manager).path("jobCard").path("sourceType").asString()).isEqualTo("BREAKDOWN");
  }

  @Test
  void slaBreachAndEscalationFromWorkflow() throws Exception {
    Society s = new Society();
    UUID estateManager = UuidV7.next();
    send("sos.identity.events.v1", s.id, "identity.role.assigned", Map.of("assignmentId", UuidV7.next(),
        "userId", estateManager, "roleCode", "ESTATE_MANAGER", "source", "MANUAL"));
    await("role holder known", () -> count("select count(*) from role_holder where user_id = '" + estateManager + "'") == 1);

    UUID complaintId = id(post("/v1/complaints", Map.of("text", "Lift stuck on 3rd floor", "priority", "P1"), s.resident));
    UUID cardId = id(post("/v1/complaints/" + complaintId + "/jobcard", Map.of("assigneeUserId", s.techId), s.manager));

    // workflow-service's timer fired: breach, then the first escalation step
    send("sos.workflow.events.v1", s.id, "workflow.sla.breached", Map.of("timerId", UuidV7.next(),
        "subjectType", "COMPLAINT", "subjectId", complaintId, "kind", "RESOLVE", "dueAt", Instant.now().toString()));
    send("sos.workflow.events.v1", s.id, "workflow.escalated", Map.of("subjectType", "COMPLAINT",
        "subjectId", complaintId, "level", 1, "toRole", "ESTATE_MANAGER"));

    await("escalated", () -> get("/v1/complaints/" + complaintId, s.manager).path("complaint").path("escalationLevel").asInt() == 1);
    JsonNode c = get("/v1/complaints/" + complaintId, s.manager).path("complaint");
    assertThat(c.path("slaBreached").asBoolean()).isTrue();
    assertThat(c.path("escalatedToRole").asString()).isEqualTo("ESTATE_MANAGER");
    JsonNode card = get("/v1/jobcards/" + cardId, s.manager).path("jobCard");
    assertThat(card.path("slaBreached").asBoolean()).isTrue();
    assertThat(card.path("escalationLevel").asInt()).isEqualTo(1);
    // The estate manager, the technician and the resident are all alerted
    String alert = "select count(*) from outbox_event where type = 'ticket.notification.requested'"
        + " and payload->'data'->>'template' = 'ticket.escalated' and payload->'data'->>'priority' = 'HIGH'";
    assertThat(count(alert + " and payload::text like '%" + estateManager + "%' and payload::text like '%"
        + s.residentId + "%' and payload::text like '%" + s.techId + "%'")).isEqualTo(1);
    assertThat(count("select count(*) from outbox_event where type = 'ticket.notification.requested'"
        + " and payload->'data'->>'template' = 'sla.breached' and society_id = '" + s.id + "'")).isEqualTo(1);
  }

  @Test
  void expensiveJobCardWaitsForWorkflowApproval() throws Exception {
    Society s = new Society();
    UUID complaintId = id(post("/v1/complaints", Map.of("text", "Water pump motor burnt"), s.resident));
    UUID cardId = id(post("/v1/complaints/" + complaintId + "/jobcard", Map.of("assigneeUserId", s.techId), s.manager));
    transition(cardId, Map.of("action", "START"), s.tech);
    post("/v1/jobcards/" + cardId + "/work-logs", Map.of("note", "Motor rewinding", "minutes", 240,
        "costPaise", 800_000), s.tech);
    post("/v1/jobcards/" + cardId + "/evidence", Map.of("stage", "AFTER", "mediaId", UuidV7.next()), s.tech);
    JsonNode done = transition(cardId, Map.of("action", "COMPLETE", "workDone", "Rewound", "rootCause", "Burnt"), s.tech);
    assertThat(done.path("approvalStatus").asString()).isEqualTo("PENDING");
    assertThat(count("select count(*) from outbox_event where type = 'ticket.jobcard.completed' and payload->'data'->>'totalCostPaise' = '800000'"))
        .isEqualTo(1);
    assertThat(transition(cardId, Map.of("action", "VERIFY"), s.manager).path("code").asString()).isEqualTo("APPROVAL_PENDING");

    UUID instanceId = UuidV7.next();
    send("sos.workflow.events.v1", s.id, "workflow.approval.requested", Map.of("taskId", UuidV7.next(),
        "instanceId", instanceId, "subjectType", "JOBCARD", "subjectId", cardId, "step", "1",
        "approverRole", "RWA_COMMITTEE", "amountPaise", 800_000));
    send("sos.workflow.events.v1", s.id, "workflow.instance.approved", Map.of("instanceId", instanceId,
        "subjectType", "JOBCARD", "subjectId", cardId, "decidedBy", UuidV7.next(), "comment", "OK"));
    await("approved", () -> "APPROVED".equals(get("/v1/jobcards/" + cardId, s.manager).path("jobCard").path("approvalStatus").asString()));
    assertThat(transition(cardId, Map.of("action", "VERIFY"), s.manager).path("status").asString()).isEqualTo("VERIFIED");
  }

  @Test
  void societiesCannotReadEachOthersTickets() throws Exception {
    Society a = new Society();
    Society b = new Society();
    UUID complaintId = id(post("/v1/complaints", Map.of("text", "Corridor light fused"), a.resident));
    UUID cardId = id(post("/v1/complaints/" + complaintId + "/jobcard", Map.of("assigneeUserId", a.techId), a.manager));

    assertThat(get("/v1/complaints/" + complaintId, b.manager).path("code").asString()).isEqualTo("COMPLAINT_NOT_FOUND");
    assertThat(get("/v1/jobcards/" + cardId, b.manager).path("code").asString()).isEqualTo("JOB_CARD_NOT_FOUND");
    assertThat(get("/v1/complaints", b.manager).size()).isZero();
    assertThat(get("/v1/jobcards", b.manager).size()).isZero();
    JsonNode crossWrite = put("/v1/jobcards/" + cardId + "/assignment", Map.of("assigneeUserId", b.techId), b.manager);
    assertThat(crossWrite.path("status").asInt()).isEqualTo(404);

    // And in the database itself: the runtime role with society B's setting sees none of A's rows
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app", "app")) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("select set_config('app.society_ids', '{" + b.id + "}', true)");
        try (ResultSet rs = st.executeQuery("select count(*) from complaint where society_id = '" + a.id + "'")) {
          rs.next();
          assertThat(rs.getLong(1)).isZero();
        }
        try (ResultSet rs = st.executeQuery("select count(*) from job_card")) {
          rs.next();
          assertThat(rs.getLong(1)).isZero();
        }
      }
      c.rollback();
    }
  }

  // --- helpers ---------------------------------------------------------------------------

  void send(String topic, UUID societyId, String type, Map<String, Object> data) throws Exception {
    UUID eventId = UuidV7.next();
    Map<String, Object> envelope = new HashMap<>();
    envelope.put("specversion", "1.0");
    envelope.put("id", eventId);
    envelope.put("source", "test");
    envelope.put("type", type);
    envelope.put("time", Instant.now().toString());
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

  JsonNode transition(UUID cardId, Map<String, Object> body, String token) {
    return post("/v1/jobcards/" + cardId + "/transitions", body, token);
  }

  static UUID id(JsonNode node) {
    String id = node.path("id").asString();
    assertThat(id).as(node.toString()).isNotBlank();
    return UUID.fromString(id);
  }

  JsonNode get(String path, String token) {
    return http.get().uri(path).header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
  }

  JsonNode post(String path, Object body, String token) {
    return http.post().uri(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
  }

  JsonNode put(String path, Object body, String token) {
    return http.put().uri(path).header("Authorization", "Bearer " + token)
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
