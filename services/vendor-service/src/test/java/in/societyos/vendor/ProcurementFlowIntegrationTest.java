package in.societyos.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import in.societyos.vendor.platform.core.UuidV7;
import in.societyos.vendor.platform.core.tenant.TenantContext;
import in.societyos.vendor.platform.security.IdentityPermissionsClient;
import in.societyos.vendor.platform.test.IntegrationTestBase;
import in.societyos.vendor.platform.test.TestJwtIssuer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * vendor-service end to end: real Postgres (RLS on), Kafka and Redis. workflow-service is
 * simulated by publishing its events; identity permissions are stubbed per user.
 */
class ProcurementFlowIntegrationTest extends IntegrationTestBase {

  static final List<String> MANAGER = List.of("vendor:view", "vendor:manage", "rfq:manage", "po:create",
      "grn:record", "invoice:approve", "vendorpayment:record");
  static final List<String> VENDOR = List.of("jobcard:work", "amc:visit", "invoice:submit");

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

  /** A society with an estate manager and one vendor whose staff member uses the portal. */
  class Society {
    final UUID id = UuidV7.next();
    final UUID managerId = user(MANAGER);
    final UUID vendorUserId = user(VENDOR);
    final String manager = TestJwtIssuer.token(managerId, id, "ESTATE_MANAGER");
    final String vendorUser = TestJwtIssuer.token(vendorUserId, id, "VENDOR");
    final UUID vendorId;

    Society(String vendorName) {
      JsonNode v = post("/v1/vendors", Map.of("name", vendorName, "category", "plumbing",
          "workScopes", List.of("pumps", "pipes"), "gstin", "06ABCDE1234F1Z5", "pan", "ABCDE1234F",
          "contactName", "Raj", "contactPhone", "98765 43210", "contactEmail", "raj@aqua.example"), manager);
      assertThat(v.path("contactPhoneMasked").asString()).as(v.toString()).isEqualTo("98XXXXXX10");
      assertThat(v.path("panMasked").asString()).isEqualTo("XXXXXX234F");
      vendorId = id(v);
      post("/v1/agents", Map.of("vendorId", vendorId, "userId", vendorUserId, "code", "AG-" + vendorName.hashCode(),
          "name", "Raj", "role", "supervisor"), manager);
    }
  }

  UUID user(List<String> permissions) {
    UUID id = UuidV7.next();
    permissionsOf.put(id, permissions);
    return id;
  }

  @Test
  void rfqToPoApprovedByWorkflowThenPartialGrnsAndAThreeWayMatchedInvoice() throws Exception {
    Society s = new Society("Aqua Pumps");
    assertThat(outbox("vendor.vendor.created", s.id)).isEqualTo(1);
    assertThat(outbox("vendor.agent.created", s.id)).isEqualTo(1);
    // No phone, e-mail or PAN ever reaches an event
    assertThat(count("select count(*) from outbox_event where payload::text like '%43210%' or payload::text like "
        + "'%aqua.example%' or payload::text like '%ABCDE1234F\"%'")).isZero();

    // RFQ → the vendor quotes through the portal → comparison → award raises a draft PO
    UUID spareId = UuidV7.next();
    UUID storeId = UuidV7.next();
    JsonNode rfq = post("/v1/rfqs", Map.of("title", "Booster pump spares", "storeId", storeId,
        "invitedVendorIds", List.of(s.vendorId), "lines", List.of(
            Map.of("itemCode", "seal-kit", "spareId", spareId, "description", "Mechanical seal kit", "qty", 5))), s.manager);
    UUID rfqId = id(rfq);
    UUID rfqLine = UUID.fromString(rfq.path("lines").get(0).path("id").asString());
    assertThat(get("/v1/vendor-portal/rfqs", s.vendorUser).size()).isEqualTo(1);
    JsonNode quote = post("/v1/vendor-portal/rfqs/" + rfqId + "/quotes", Map.of("deliveryDays", 3,
        "lines", List.of(Map.of("rfqLineId", rfqLine, "unitPricePaise", 120_000, "gstPercent", 18))), s.vendorUser);
    assertThat(quote.path("totalPaise").asLong()).as(quote.toString()).isEqualTo(708_000);
    JsonNode comparison = get("/v1/rfqs/" + rfqId + "/comparison", s.manager);
    assertThat(comparison.path("quotes").get(0).path("rank").asInt()).isEqualTo(1);

    JsonNode po = post("/v1/rfqs/" + rfqId + "/award", Map.of("quoteId", id(quote)), s.manager);
    assertThat(po.path("status").asString()).as(po.toString()).isEqualTo("DRAFT");
    assertThat(po.path("number").asString()).startsWith("PO-");
    UUID poId = id(po);
    UUID poLine = UUID.fromString(po.path("lines").get(0).path("id").asString());
    assertThat(get("/v1/vendor-portal/purchase-orders", s.vendorUser).size()).isZero(); // drafts are not shown

    // Submit → vendor.po.submitted with exactly what workflow-service reads
    assertThat(post("/v1/purchase-orders/" + poId + "/submit", Map.of(), s.manager).path("status").asString())
        .isEqualTo("SUBMITTED");
    assertThat(count("select count(*) from outbox_event where type = 'vendor.po.submitted' and payload->'data'->>'poId' = '"
        + poId + "' and payload->'data'->>'amountPaise' = '708000' and payload->'data'->>'number' like 'PO-%'"
        + " and payload->'data'->>'vendorId' = '" + s.vendorId + "'")).isEqualTo(1);
    // Receiving before approval is refused
    assertThat(post("/v1/grns", grn(poId, poLine, 1), s.manager).path("code").asString()).isEqualTo("PO_NOT_RECEIVABLE");

    UUID instanceId = UuidV7.next();
    send("sos.workflow.events.v1", s.id, "workflow.approval.requested", Map.of("taskId", UuidV7.next(),
        "instanceId", instanceId, "subjectType", "PO", "subjectId", poId, "step", "1",
        "approverRole", "ESTATE_MANAGER", "amountPaise", 708_000));
    send("sos.workflow.events.v1", s.id, "workflow.instance.approved", Map.of("instanceId", instanceId,
        "subjectType", "PO", "subjectId", poId, "decidedBy", UuidV7.next(), "comment", "Within budget"));
    await("PO approved", () -> "APPROVED".equals(get("/v1/purchase-orders/" + poId, s.manager).path("status").asString()));
    JsonNode approved = get("/v1/purchase-orders/" + poId, s.manager);
    assertThat(approved.path("workflowInstanceId").asString()).isEqualTo(instanceId.toString());
    assertThat(approved.path("decisionComment").asString()).isEqualTo("Within budget");
    assertThat(outbox("vendor.po.approved", s.id)).isEqualTo(1);
    // A redelivered decision changes nothing
    send("sos.workflow.events.v1", s.id, "workflow.instance.approved", Map.of("instanceId", instanceId,
        "subjectType", "PO", "subjectId", poId, "decidedBy", UuidV7.next(), "comment", "again"));
    assertThat(get("/v1/vendor-portal/purchase-orders/" + poId, s.vendorUser).path("status").asString()).isEqualTo("APPROVED");

    // First GRN: 4 delivered, 3 accepted → partially received; inventory gets the accepted qty
    JsonNode grn1 = post("/v1/grns", Map.of("poId", poId, "challanRef", "DC-77", "lines",
        List.of(Map.of("poLineId", poLine, "receivedQty", 4, "acceptedQty", 3))), s.manager);
    assertThat(grn1.path("number").asString()).as(grn1.toString()).startsWith("GRN-");
    assertThat(get("/v1/purchase-orders/" + poId, s.manager).path("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
    assertThat(count("select count(*) from outbox_event where type = 'vendor.grn.recorded' and payload->'data'->>'storeId' = '"
        + storeId + "' and payload->'data'->'lines'->0->>'spareId' = '" + spareId + "' and payload->'data'->'lines'->0->>'qty' = '3'"
        + " and payload->'data'->'lines'->0->>'unitCostPaise' = '120000' and payload->'data'->'lines'->0->>'itemCode' = 'SEAL-KIT'"))
        .isEqualTo(1);
    // Over-receipt is refused (2 pending)
    assertThat(post("/v1/grns", grn(poId, poLine, 3), s.manager).path("code").asString()).isEqualTo("OVER_RECEIPT");

    // The vendor bills all 5 through the portal: only 3 were accepted → MISMATCH, cannot be approved
    JsonNode inv = post("/v1/vendor-portal/invoices", Map.of("poId", poId, "vendorInvoiceNo", "aq/2026/15",
        "invoiceDate", LocalDate.now().toString(), "totalPaise", 708_000, "lines",
        List.of(Map.of("poLineId", poLine, "qty", 5, "unitPricePaise", 120_000, "taxPaise", 108_000))), s.vendorUser);
    assertThat(inv.path("status").asString()).as(inv.toString()).isEqualTo("MISMATCH");
    assertThat(inv.path("matchIssues").get(0).asString()).startsWith("QTY_EXCEEDS_RECEIVED");
    UUID invoiceId = id(inv);
    assertThat(post("/v1/invoices/" + invoiceId + "/approve", Map.of(), s.manager).path("code").asString())
        .isEqualTo("INVOICE_NOT_MATCHED");
    JsonNode dup = post("/v1/vendor-portal/invoices", Map.of("poId", poId, "vendorInvoiceNo", "AQ/2026/15",
        "invoiceDate", LocalDate.now().toString(), "lines",
        List.of(Map.of("poLineId", poLine, "qty", 1, "unitPricePaise", 120_000))), s.vendorUser);
    assertThat(dup.path("code").asString()).isEqualTo("DUPLICATE_INVOICE");

    // Second GRN for the rest → RECEIVED; re-match → MATCHED; approve → vendor.invoice.approved
    post("/v1/grns", grn(poId, poLine, 2), s.manager);
    assertThat(get("/v1/purchase-orders/" + poId, s.manager).path("status").asString()).isEqualTo("RECEIVED");
    assertThat(post("/v1/invoices/" + invoiceId + "/rematch", Map.of(), s.manager).path("status").asString())
        .isEqualTo("MATCHED");
    JsonNode ok = post("/v1/invoices/" + invoiceId + "/approve", Map.of("comment", "3-way matched"), s.manager);
    assertThat(ok.path("status").asString()).as(ok.toString()).isEqualTo("APPROVED");
    assertThat(count("select count(*) from outbox_event where type = 'vendor.invoice.approved' and payload->'data'->>'invoiceId' = '"
        + invoiceId + "' and payload->'data'->>'amountPaise' = '708000' and payload->'data'->>'poId' = '" + poId + "'"))
        .isEqualTo(1);

    // Payments: partial, then the rest; overpaying is refused
    post("/v1/invoices/" + invoiceId + "/payments", Map.of("amountPaise", 500_000, "paidOn", LocalDate.now().toString(),
        "mode", "neft", "reference", "UTR1"), s.manager);
    assertThat(post("/v1/invoices/" + invoiceId + "/payments", Map.of("amountPaise", 300_000,
        "paidOn", LocalDate.now().toString(), "mode", "NEFT"), s.manager).path("code").asString()).isEqualTo("OVERPAYMENT");
    JsonNode paid = post("/v1/invoices/" + invoiceId + "/payments", Map.of("amountPaise", 208_000,
        "paidOn", LocalDate.now().toString(), "mode", "UPI"), s.manager);
    assertThat(paid.path("status").asString()).isEqualTo("PAID");
    assertThat(get("/v1/vendor-portal/invoices/" + invoiceId, s.vendorUser).path("paidPaise").asLong()).isEqualTo(708_000);
  }

  @Test
  void workflowRejectionAndVendorPortalScoping() throws Exception {
    Society s = new Society("Spark Electricals");
    UUID otherVendor = id(post("/v1/vendors", Map.of("name", "Other Co", "category", "ELECTRICAL"), s.manager));
    UUID mine = id(post("/v1/purchase-orders", po(s.vendorId, "Cables"), s.manager));
    UUID theirs = id(post("/v1/purchase-orders", po(otherVendor, "Lamps"), s.manager));
    post("/v1/purchase-orders/" + mine + "/submit", Map.of(), s.manager);
    post("/v1/purchase-orders/" + theirs + "/submit", Map.of(), s.manager);

    send("sos.workflow.events.v1", s.id, "workflow.instance.rejected", Map.of("instanceId", UuidV7.next(),
        "subjectType", "PO", "subjectId", mine, "decidedBy", UuidV7.next(), "comment", "Too costly"));
    // Other subject types on the topic are ignored
    send("sos.workflow.events.v1", s.id, "workflow.instance.approved", Map.of("instanceId", UuidV7.next(),
        "subjectType", "JOBCARD", "subjectId", theirs, "decidedBy", UuidV7.next()));
    await("PO rejected", () -> "REJECTED".equals(get("/v1/purchase-orders/" + mine, s.manager).path("status").asString()));
    assertThat(outbox("vendor.po.rejected", s.id)).isEqualTo(1);
    assertThat(get("/v1/purchase-orders/" + theirs, s.manager).path("status").asString()).isEqualTo("SUBMITTED");

    // The vendor sees only its own PO; the other vendor's PO is a 404, staff APIs are forbidden
    JsonNode list = get("/v1/vendor-portal/purchase-orders", s.vendorUser);
    assertThat(list.size()).isEqualTo(1);
    assertThat(list.get(0).path("id").asString()).isEqualTo(mine.toString());
    assertThat(get("/v1/vendor-portal/purchase-orders/" + theirs, s.vendorUser).path("code").asString())
        .isEqualTo("PURCHASE_ORDER_NOT_FOUND");
    assertThat(status("/v1/purchase-orders", s.vendorUser)).isEqualTo(403);
    assertThat(get("/v1/vendor-portal/me", s.vendorUser).path("name").asString()).isEqualTo("Spark Electricals");
    // A user with vendor permissions but no agent record is refused
    String stranger = TestJwtIssuer.token(user(VENDOR), s.id, "VENDOR");
    assertThat(get("/v1/vendor-portal/me", stranger).path("code").asString()).isEqualTo("NOT_A_VENDOR");
    // Invoicing a rejected PO is refused
    assertThat(post("/v1/vendor-portal/invoices", Map.of("poId", mine, "vendorInvoiceNo", "X1",
        "invoiceDate", LocalDate.now().toString(), "lines", List.of(Map.of("poLineId", UuidV7.next(), "qty", 1,
            "unitPricePaise", 1))), s.vendorUser).path("code").asString()).isEqualTo("PO_NOT_INVOICEABLE");
  }

  @Test
  void societiesAreIsolated() throws Exception {
    Society a = new Society("Alpha Lifts");
    Society b = new Society("Beta Lifts");
    UUID poA = id(post("/v1/purchase-orders", po(a.vendorId, "Lift ropes"), a.manager));
    post("/v1/purchase-orders/" + poA + "/submit", Map.of(), a.manager);

    assertThat(get("/v1/vendors/" + a.vendorId, b.manager).path("code").asString()).isEqualTo("VENDOR_NOT_FOUND");
    assertThat(get("/v1/purchase-orders/" + poA, b.manager).path("code").asString()).isEqualTo("PURCHASE_ORDER_NOT_FOUND");
    assertThat(get("/v1/vendors", b.manager).size()).isEqualTo(1);
    assertThat(post("/v1/purchase-orders", po(a.vendorId, "Cross"), b.manager).path("code").asString())
        .isEqualTo("VENDOR_NOT_FOUND");
    assertThat(get("/v1/vendor-portal/purchase-orders/" + poA, b.vendorUser).path("code").asString())
        .isEqualTo("PURCHASE_ORDER_NOT_FOUND");

    // A workflow decision carrying society B cannot touch society A's PO
    send("sos.workflow.events.v1", b.id, "workflow.instance.approved", Map.of("instanceId", UuidV7.next(),
        "subjectType", "PO", "subjectId", poA, "decidedBy", UuidV7.next()));
    UUID poB = id(post("/v1/purchase-orders", po(b.vendorId, "Marker"), b.manager));
    post("/v1/purchase-orders/" + poB + "/submit", Map.of(), b.manager);
    send("sos.workflow.events.v1", b.id, "workflow.instance.approved", Map.of("instanceId", UuidV7.next(),
        "subjectType", "PO", "subjectId", poB, "decidedBy", UuidV7.next()));
    await("B's PO approved", () -> "APPROVED".equals(get("/v1/purchase-orders/" + poB, b.manager).path("status").asString()));
    assertThat(get("/v1/purchase-orders/" + poA, a.manager).path("status").asString()).isEqualTo("SUBMITTED");

    // And in the database: the runtime role with society B's setting sees none of A's rows
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app", "app")) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("select set_config('app.society_ids', '{" + b.id + "}', true)");
        try (ResultSet rs = st.executeQuery("select count(*) from purchase_order where society_id = '" + a.id + "'")) {
          rs.next();
          assertThat(rs.getLong(1)).isZero();
        }
        try (ResultSet rs = st.executeQuery("select count(*) from vendor")) {
          rs.next();
          assertThat(rs.getLong(1)).isEqualTo(1);
        }
      }
      c.rollback();
    }
  }

  // --- helpers ---------------------------------------------------------------------------

  static Map<String, Object> po(UUID vendorId, String title) {
    return Map.of("vendorId", vendorId, "title", title, "lines",
        List.of(Map.of("description", title, "qty", 2, "unitPricePaise", 50_000, "gstPercent", 18)));
  }

  static Map<String, Object> grn(UUID poId, UUID poLine, int qty) {
    return Map.of("poId", poId, "lines", List.of(Map.of("poLineId", poLine, "receivedQty", qty)));
  }

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

  static UUID id(JsonNode node) {
    String id = node.path("id").asString();
    assertThat(id).as(node.toString()).isNotBlank();
    return UUID.fromString(id);
  }

  JsonNode get(String path, String token) {
    return http.get().uri(path).header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
  }

  int status(String path, String token) {
    return http.get().uri(path).header("Authorization", "Bearer " + token)
        .exchange((req, res) -> res.getStatusCode().value());
  }

  JsonNode post(String path, Object body, String token) {
    return http.post().uri(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
  }

  long outbox(String type, UUID societyId) throws Exception {
    return count("select count(*) from outbox_event where type = '" + type + "' and society_id = '" + societyId + "'");
  }

  long count(String sql) throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
