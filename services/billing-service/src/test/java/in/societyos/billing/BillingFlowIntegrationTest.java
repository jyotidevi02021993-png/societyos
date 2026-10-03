package in.societyos.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import in.societyos.billing.jobs.application.BillingJobs;
import in.societyos.billing.platform.core.UuidV7;
import in.societyos.billing.platform.core.tenant.TenantContext;
import in.societyos.billing.platform.security.IdentityPermissionsClient;
import in.societyos.billing.platform.test.IntegrationTestBase;
import in.societyos.billing.platform.test.TestJwtIssuer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * End to end inside billing-service: real Postgres (RLS on), Kafka and Redis; identity-service
 * permissions stubbed per user; society data arrives as society-service events.
 */
class BillingFlowIntegrationTest extends IntegrationTestBase {

  static final List<String> ACCOUNTS = List.of("bill:generate", "bill:view", "payment:record", "payment:view",
      "expense:record", "expense:view", "ledger:view", "vendorpayment:record");
  static final List<String> RESIDENT = List.of("bill:view-own", "bill:pay");
  static final List<String> COMMITTEE = List.of("budget:approve", "expense:view", "bill:view");
  static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @LocalServerPort int port;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper json;
  @Autowired BillingJobs jobs;

  RestClient http;
  final Map<UUID, List<String>> permissionsOf = new ConcurrentHashMap<>();

  UUID society;
  UUID flatA;
  UUID flatB;
  UUID residentId;
  String accounts;
  String resident;

  @BeforeEach
  void setUp() throws Exception {
    http = RestClient.builder().baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();
    when(identityPermissions.myPermissions()).thenAnswer(inv -> new IdentityPermissionsClient.Permissions(null,
        permissionsOf.getOrDefault(TenantContext.userId().orElse(null), List.of())));

    society = UuidV7.next();
    flatA = UuidV7.next();
    flatB = UuidV7.next();
    residentId = UuidV7.next();
    UUID accountant = UuidV7.next();
    permissionsOf.put(accountant, ACCOUNTS);
    permissionsOf.put(residentId, RESIDENT);
    accounts = TestJwtIssuer.token(accountant, society, "ACCOUNTS");
    resident = TestJwtIssuer.token(residentId, society, "RESIDENT_OWNER");
    seedSociety(society, flatA, flatB, residentId);
  }

  /** The society copy is built from society-service events, as in production. */
  void seedSociety(UUID society, UUID flatA, UUID flatB, UUID resident) throws Exception {
    sendEvent("sos.society.events.v1", society, "society.settings.updated", Map.of("societyId", society,
        "settings", Map.of("billingDueDay", 10, "lateFeeGraceDays", 5, "gateApprovalTimeoutSeconds", 120)));
    sendEvent("sos.society.events.v1", society, "society.flat.created", flat(flatA, "A", "101", 1000, "2BHK"));
    sendEvent("sos.society.events.v1", society, "society.flat.created", flat(flatB, "B", "201", 2500, "3BHK"));
    sendEvent("sos.society.events.v1", society, "society.membership.created", Map.of("membershipId", UuidV7.next(),
        "flatId", flatA, "userId", resident, "residentId", UuidV7.next(), "residentName", "Asha Rao",
        "kind", "OWNER", "isPrimary", true));
    await(() -> count("select count(*) from flat_ref where society_id = '" + society + "'") == 2);
    await(() -> count("select count(*) from flat_member where flat_id = '" + flatA + "'") == 1);
    await(() -> count("select count(*) from billing_settings where id = '" + society
        + "' and billing_due_day = 10 and late_fee_grace_days = 5") == 1);
  }

  static Map<String, Object> flat(UUID id, String tower, String number, int area, String type) {
    return Map.of("flatId", id, "towerId", UuidV7.next(), "towerName", "Tower " + tower, "number", number,
        "label", tower + "-" + number, "floor", 1, "areaSqft", area, "flatType", type, "status", "OCCUPIED");
  }

  @Test
  void billRunPublishPayReceiptZeroOutstanding() throws Exception {
    put("/v1/billing/settings", Map.of("gstRegistered", true), accounts);
    post("/v1/charge-heads", Map.of("code", "MAINT", "name", "Maintenance", "basis", "PER_SQFT", "ratePaise", 350,
        "sortOrder", 1), accounts);
    post("/v1/charge-heads", Map.of("code", "SINK", "name", "Sinking fund", "basis", "FIXED", "ratePaise", 50_000,
        "gstApplicable", false, "sortOrder", 2), accounts);
    JsonNode club = post("/v1/charge-heads", Map.of("code", "CLUB", "name", "Club", "basis", "FLAT_TYPE",
        "ratePaise", 0, "flatTypeRates", Map.of("2bhk", 30_000, "3BHK", 45_000), "sortOrder", 3), accounts);
    assertThat(club.path("flatTypeRates").path("2BHK").asLong()).isEqualTo(30_000);
    assertThat(post("/v1/charge-heads", Map.of("code", "maint", "name", "Again", "basis", "FIXED", "ratePaise", 1),
        accounts).path("code").asString()).isEqualTo("CHARGE_HEAD_EXISTS");

    // A chargeable facility booking (community-service) becomes a one-off charge on the next bill
    UUID booking = UuidV7.next();
    sendEvent("sos.community.events.v1", society, "community.booking.confirmed", Map.of("bookingId", booking,
        "facilityId", UuidV7.next(), "flatId", flatA, "userId", residentId, "startsAt", Instant.now().toString(),
        "endsAt", Instant.now().toString(), "chargePaise", 150_000));
    await(() -> count("select count(*) from pending_charge where source_ref = '" + booking + "'") == 1);

    // Preview: A = 3,50,000 + 50,000 + 30,000 + 1,50,000 booking, no GST (taxable 3,80,000 <= 7,50,000)
    //          B = 8,75,000 + 50,000 + 45,000; taxable 9,20,000 > threshold → GST 1,57,500 + 8,100
    String period = YearMonth.now(IST).toString();
    JsonNode preview = post("/v1/bill-runs", Map.of("period", period), accounts);
    assertThat(preview.path("status").asString()).as(preview.toString()).isEqualTo("PREVIEW");
    assertThat(preview.path("billCount").asInt()).isEqualTo(2);
    assertThat(preview.path("gstPaise").asLong()).isEqualTo(165_600);
    assertThat(preview.path("totalPaise").asLong()).isEqualTo(580_000 + 1_135_600);
    assertThat(outboxCount("billing.bill.generated")).isZero();
    // Recomputing the preview replaces it
    UUID runId = id(post("/v1/bill-runs", Map.of("period", period), accounts));
    assertThat(count("select count(*) from bill_run where society_id = '" + society + "' and status = 'DISCARDED'"))
        .isEqualTo(1);

    JsonNode published = post("/v1/bill-runs/" + runId + "/publish", Map.of(), accounts);
    assertThat(published.path("status").asString()).as(published.toString()).isEqualTo("PUBLISHED");
    UUID billA = billOf(published, "A-101");
    UUID billB = billOf(published, "B-201");
    assertThat(outboxCount("billing.bill.generated")).isEqualTo(2);
    assertThat(outboxCount("billing.billrun.completed")).isEqualTo(1);
    assertThat(outboxCount("billing.notification.requested")).isEqualTo(1); // only flat A has a member
    assertThat(count("select count(*) from pending_charge where source_ref = '" + booking + "' and status = 'BILLED'"))
        .isEqualTo(1);
    assertThat(post("/v1/bill-runs/" + runId + "/publish", Map.of(), accounts).path("code").asString())
        .isEqualTo("BILL_RUN_PUBLISHED");
    assertThat(post("/v1/bill-runs", Map.of("period", period), accounts).path("code").asString())
        .isEqualTo("BILL_RUN_PUBLISHED");

    JsonNode detailA = get("/v1/bills/" + billA, accounts);
    assertThat(detailA.path("bill").path("number").asString()).startsWith("BILL-");
    assertThat(detailA.path("bill").path("totalPaise").asLong()).isEqualTo(580_000);
    assertThat(detailA.path("lines").size()).isEqualTo(4);

    // The resident sees only their own flat
    JsonNode mine = get("/v1/bills", resident);
    assertThat(mine.size()).isEqualTo(1);
    assertThat(mine.get(0).path("flatLabel").asString()).isEqualTo("A-101");
    assertThat(get("/v1/bills?flatId=" + flatB, resident).path("code").asString()).isEqualTo("NOT_YOUR_FLAT");
    assertThat(get("/v1/bills/" + billB, resident).path("code").asString()).isEqualTo("NOT_YOUR_FLAT");
    assertThat(post("/v1/bills/" + billB + "/pay", Map.of(), resident).path("code").asString())
        .isEqualTo("NOT_YOUR_FLAT");
    assertThat(get("/v1/me/dues", resident).get(0).path("outstandingPaise").asLong()).isEqualTo(580_000);
    assertThat(status("GET", "/v1/dues", resident)).isEqualTo(403);

    // Online payment: order, then a signed webhook (a replay has no effect)
    JsonNode order = post("/v1/bills/" + billA + "/pay", Map.of(), resident);
    String orderId = order.path("orderId").asString();
    assertThat(orderId).as(order.toString()).startsWith("order_");
    assertThat(order.path("amountPaise").asLong()).isEqualTo(580_000);
    String hook = json.writeValueAsString(Map.of("id", "evt_" + orderId, "event", "payment.captured",
        "orderId", orderId, "paymentId", "pay_" + orderId, "amountPaise", 580_000));
    assertThat(webhook(hook, "deadbeef").path("code").asString()).isEqualTo("INVALID_SIGNATURE");
    assertThat(webhook(hook, sign(hook)).path("result").asString()).isEqualTo("PROCESSED");
    assertThat(webhook(hook, sign(hook)).path("result").asString()).isEqualTo("DUPLICATE");

    assertThat(get("/v1/bills/" + billA, resident).path("bill").path("status").asString()).isEqualTo("PAID");
    JsonNode receipts = get("/v1/receipts", resident);
    assertThat(receipts.size()).isEqualTo(1);
    assertThat(receipts.get(0).path("number").asString()).startsWith("RCPT-");
    assertThat(receipts.get(0).path("amountPaise").asLong()).isEqualTo(580_000);
    assertThat(outboxCount("billing.payment.succeeded")).isEqualTo(1);
    assertThat(outboxCount("billing.receipt.issued")).isEqualTo(1);
    assertThat(get("/v1/me/dues", resident).get(0).path("outstandingPaise").asLong()).isZero();

    // Offline payments settle flat B in two parts
    assertThat(post("/v1/payments", Map.of("billId", billB, "amountPaise", 100, "method", "UPI"), accounts)
        .path("code").asString()).isEqualTo("REFERENCE_REQUIRED");
    JsonNode cash = post("/v1/payments", Map.of("billId", billB, "amountPaise", 500_000, "method", "CASH"), accounts);
    assertThat(cash.path("receipt").path("number").asString()).as(cash.toString()).startsWith("RCPT-");
    assertThat(get("/v1/bills/" + billB, accounts).path("bill").path("status").asString()).isEqualTo("PART_PAID");
    post("/v1/payments", Map.of("billId", billB, "amountPaise", 635_600, "method", "UPI", "reference", "UTR123"),
        accounts);
    assertThat(get("/v1/bills/" + billB, accounts).path("bill").path("balancePaise").asLong()).isZero();

    JsonNode dues = get("/v1/dues", accounts);
    assertThat(dues.size()).isEqualTo(2);
    dues.forEach(d -> assertThat(d.path("outstandingPaise").asLong()).isZero());

    // The ledger balances, GST is payable, receivables are cleared
    JsonNode tb = get("/v1/ledger/accounts", accounts);
    long debit = 0;
    long credit = 0;
    for (JsonNode a : tb) {
      debit += a.path("debitPaise").asLong();
      credit += a.path("creditPaise").asLong();
      if (a.path("code").asString().equals("MEMBER_RECEIVABLE")) {
        assertThat(a.path("balancePaise").asLong()).isZero();
      }
      if (a.path("code").asString().equals("GST_PAYABLE")) {
        assertThat(a.path("creditPaise").asLong()).isEqualTo(165_600);
      }
    }
    assertThat(debit).isEqualTo(credit).isEqualTo(1_715_600L * 2);
    LocalDate today = LocalDate.now(IST);
    String csv = http.get().uri("/v1/ledger/export.csv?from=" + today.minusDays(1) + "&to=" + today.plusDays(1))
        .header("Authorization", "Bearer " + accounts).retrieve().body(String.class);
    assertThat(csv).startsWith("date,txn_id,account,flat_id,debit,credit");
    assertThat(csv).contains("MEMBER_RECEIVABLE", "5800.00");
    assertThat(get("/v1/flats/" + flatA + "/statement", resident).size()).isEqualTo(2);

    // Events carry no personal data
    assertThat(count("select count(*) from outbox_event where payload::text like '%Asha%'")).isZero();
  }

  @Test
  void lateFeeCreditNoteAndAdvance() throws Exception {
    put("/v1/billing/settings", Map.of("gstRegistered", false, "lateFeeKind", "PERCENT", "lateFeeValue", 200),
        accounts);
    post("/v1/charge-heads", Map.of("code", "MAINT", "name", "Maintenance", "basis", "FIXED", "ratePaise", 100_000,
        "gstApplicable", false), accounts);
    YearMonth month = YearMonth.now(IST);
    UUID run = id(post("/v1/bill-runs", Map.of("period", month.toString()), accounts));
    UUID billA = billOf(post("/v1/bill-runs/" + run + "/publish", Map.of(), accounts), "A-101");

    // Ten days past due with 5 grace days: overdue notice and a 2% late fee, once
    exec("update bill set due_date = '" + LocalDate.now(IST).minusDays(10) + "' where id = '" + billA + "'");
    jobs.dailyDues();
    jobs.dailyDues();
    JsonNode bill = get("/v1/bills/" + billA, accounts).path("bill");
    assertThat(bill.path("lateFeePaise").asLong()).isEqualTo(2_000);
    assertThat(bill.path("balancePaise").asLong()).isEqualTo(102_000);
    assertThat(outboxCount("billing.dues.overdue")).isEqualTo(1); // only A is past due, and only once
    assertThat(outbox("billing.dues.overdue", billA)).contains("\"overduePaise\":100000", "\"daysOverdue\":10");

    // Credit note waives the fee; a credit beyond the balance is refused
    JsonNode cn = post("/v1/bills/" + billA + "/adjustments",
        Map.of("kind", "CREDIT", "amountPaise", 2_000, "reason", "Late fee waived"), accounts);
    assertThat(cn.path("number").asString()).as(cn.toString()).startsWith("CN-");
    assertThat(post("/v1/bills/" + billA + "/adjustments",
        Map.of("kind", "CREDIT", "amountPaise", 200_000, "reason", "Too much"), accounts).path("code").asString())
        .isEqualTo("CREDIT_EXCEEDS_BALANCE");
    assertThat(get("/v1/bills/" + billA, accounts).path("bill").path("balancePaise").asLong()).isEqualTo(100_000);

    // An on-account payment larger than the dues leaves an advance, used by the next bill
    post("/v1/payments", Map.of("flatId", flatA, "amountPaise", 150_000, "method", "CASH"), accounts);
    assertThat(get("/v1/bills/" + billA, accounts).path("bill").path("status").asString()).isEqualTo("PAID");
    assertThat(get("/v1/flats/" + flatA + "/dues", accounts).path("outstandingPaise").asLong()).isEqualTo(-50_000);
    UUID next = id(post("/v1/bill-runs", Map.of("period", month.plusMonths(1).toString()), accounts));
    UUID nextBill = billOf(post("/v1/bill-runs/" + next + "/publish", Map.of(), accounts), "A-101");
    JsonNode n = get("/v1/bills/" + nextBill, accounts).path("bill");
    assertThat(n.path("paidPaise").asLong()).isEqualTo(50_000);
    assertThat(n.path("status").asString()).isEqualTo("PART_PAID");
    assertThat(n.path("arrearsPaise").asLong()).isEqualTo(-50_000);
    assertThat(get("/v1/flats/" + flatA + "/dues", accounts).path("outstandingPaise").asLong()).isEqualTo(50_000);
  }

  @Test
  void societiesAreIsolated() throws Exception {
    post("/v1/charge-heads", Map.of("code", "MAINT", "name", "Maintenance", "basis", "FIXED", "ratePaise", 100_000),
        accounts);
    UUID run = id(post("/v1/bill-runs", Map.of("period", YearMonth.now(IST).toString()), accounts));
    UUID billA = billOf(post("/v1/bill-runs/" + run + "/publish", Map.of(), accounts), "A-101");

    UUID otherSociety = UuidV7.next();
    UUID otherManager = UuidV7.next();
    permissionsOf.put(otherManager, ACCOUNTS);
    String other = TestJwtIssuer.token(otherManager, otherSociety, "ACCOUNTS");
    seedSociety(otherSociety, UuidV7.next(), UuidV7.next(), UuidV7.next());

    assertThat(get("/v1/bills", other).size()).isZero();
    assertThat(get("/v1/bills/" + billA, other).path("code").asString()).isEqualTo("BILL_NOT_FOUND");
    assertThat(post("/v1/bill-runs/" + run + "/publish", Map.of(), other).path("code").asString())
        .isEqualTo("BILL_RUN_NOT_FOUND");
    assertThat(post("/v1/payments", Map.of("billId", billA, "amountPaise", 100, "method", "CASH"), other)
        .path("code").asString()).isEqualTo("BILL_NOT_FOUND");
    assertThat(get("/v1/charge-heads", other).size()).isZero();
    assertThat(get("/v1/dues", other).size()).isEqualTo(2);
    get("/v1/dues", other).forEach(d -> assertThat(d.path("flatId").asString()).isNotEqualTo(flatA.toString()));

    // A society not in the token is refused before any controller runs
    JsonNode refused = json.readTree(http.get().uri("/v1/bills").header("Authorization", "Bearer " + other)
        .header("X-Society-Id", society.toString()).retrieve().body(String.class));
    assertThat(refused.path("code").asString()).isEqualTo("SOCIETY_NOT_ALLOWED");
  }

  @Test
  void expensesAndBudgetsWithMakerChecker() throws Exception {
    UUID committee = UuidV7.next();
    permissionsOf.put(committee, COMMITTEE);
    String committeeToken = TestJwtIssuer.token(committee, society, "RWA_COMMITTEE");
    UUID maker = UuidV7.next();
    permissionsOf.put(maker, List.of("expense:record", "budget:approve"));
    String makerToken = TestJwtIssuer.token(maker, society, "ACCOUNTS");

    String fy = in.societyos.billing.expense.domain.FinancialYear.of(LocalDate.now(IST));
    UUID budget = id(post("/v1/budgets", Map.of("financialYear", fy, "category", "Security",
        "amountPaise", 1_000_000), makerToken));
    assertThat(post("/v1/budgets", Map.of("financialYear", fy, "category", "security", "amountPaise", 5),
        accounts).path("code").asString()).isEqualTo("BUDGET_EXISTS");
    assertThat(status("POST", "/v1/budgets/" + budget + "/approve", accounts)).isEqualTo(403);
    assertThat(post("/v1/budgets/" + budget + "/approve", Map.of(), makerToken).path("code").asString())
        .isEqualTo("SELF_APPROVAL");
    assertThat(post("/v1/budgets/" + budget + "/approve", Map.of("note", "ok"), committeeToken)
        .path("status").asString()).isEqualTo("APPROVED");

    JsonNode expense = post("/v1/expenses", Map.of("category", "Security", "description", "Guards September",
        "amountPaise", 250_000, "paidFrom", "BANK", "vendorId", UuidV7.next()), accounts);
    assertThat(expense.path("financialYear").asString()).as(expense.toString()).isEqualTo(fy);
    assertThat(outbox("billing.expense.recorded", id(expense))).contains("\"amountPaise\":250000");
    assertThat(post("/v1/vendor-payments", Map.of("category", "AMC", "amountPaise", 10_000), accounts)
        .path("code").asString()).isEqualTo("VENDOR_REQUIRED");

    JsonNode budgets = get("/v1/budgets?financialYear=" + fy, committeeToken);
    assertThat(budgets.get(0).path("actualPaise").asLong()).isEqualTo(250_000);
    assertThat(budgets.get(0).path("remainingPaise").asLong()).isEqualTo(750_000);
    assertThat(get("/v1/expenses", committeeToken).size()).isEqualTo(1);
  }

  // --- helpers ---------------------------------------------------------------------------

  static UUID billOf(JsonNode run, String label) {
    for (JsonNode b : run.path("bills")) {
      if (b.path("flatLabel").asString().equals(label)) {
        return UUID.fromString(b.path("id").asString());
      }
    }
    throw new AssertionError("No bill for " + label + " in " + run);
  }

  static UUID id(JsonNode node) {
    String id = node.path("id").asString();
    assertThat(id).as(node.toString()).isNotBlank();
    return UUID.fromString(id);
  }

  JsonNode webhook(String body, String signature) {
    return json.readTree(http.post().uri("/v1/webhooks/stub").contentType(MediaType.APPLICATION_JSON)
        .header("X-Sos-Signature", signature).body(body).retrieve().body(String.class));
  }

  static String sign(String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("stub-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  void sendEvent(String topic, UUID societyId, String type, Map<String, Object> data) throws Exception {
    UUID id = UuidV7.next();
    Map<String, Object> envelope = Map.of("specversion", "1.0", "id", id, "source", "test", "type", type,
        "time", Instant.now().toString(), "subject", "test", "societyid", societyId, "actortype", "SYSTEM",
        "data", data);
    var record = new ProducerRecord<>(topic, societyId.toString(), json.writeValueAsString(envelope));
    record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
    record.headers().add("ce_id", id.toString().getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get();
  }

  JsonNode get(String path, String token) {
    return json.readTree(http.get().uri(path).header("Authorization", "Bearer " + token).retrieve().body(String.class));
  }

  JsonNode post(String path, Object body, String token) {
    return json.readTree(http.post().uri(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
  }

  JsonNode put(String path, Object body, String token) {
    return json.readTree(http.put().uri(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
  }

  int status(String method, String path, String token) {
    var spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
        .header("Authorization", "Bearer " + token);
    if (!"GET".equals(method)) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(Map.of());
    }
    return spec.exchange((req, res) -> res.getStatusCode().value());
  }

  long outboxCount(String type) {
    return count("select count(*) from outbox_event where type = '" + type + "' and society_id = '" + society + "'");
  }

  String outbox(String type, UUID aggregateId) {
    try (Connection c = owner(); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("select payload::text from outbox_event where type = '" + type
            + "' and aggregate_id = '" + aggregateId + "'")) {
      assertThat(rs.next()).as("outbox %s for %s", type, aggregateId).isTrue();
      return rs.getString(1).replace(" ", "");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static Connection owner() throws java.sql.SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  static void exec(String sql) throws Exception {
    try (Connection c = owner(); Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  static long count(String sql) {
    try (Connection c = owner(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void await(BooleanSupplier condition) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Condition not met in time");
  }
}
