package in.societyos.society;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import in.societyos.society.member.application.UserDirectory;
import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.core.tenant.TenantContext;
import in.societyos.society.platform.security.IdentityPermissionsClient;
import in.societyos.society.platform.test.IntegrationTestBase;
import in.societyos.society.platform.test.TestJwtIssuer;
import in.societyos.society.imports.infrastructure.ExcelWorkbooks;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End to end inside society-service: real Postgres (RLS on), Kafka and Redis; identity stubbed. */
class SocietyFlowIntegrationTest extends IntegrationTestBase {

  static final List<String> MANAGER = List.of("society:view", "society:manage", "member:manage", "member:view",
      "directory:view", "import:run", "settings:manage");
  static final List<String> RESIDENT = List.of("society:view", "household:manage", "directory:view");

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @MockitoBean UserDirectory users;

  RestClient http;
  /** Permissions per user id: the stub answers for whoever is calling. */
  final Map<UUID, List<String>> permissionsOf = new ConcurrentHashMap<>();
  /** phone → user id, as identity-service would resolve it. */
  final Map<String, UUID> userByPhone = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    http = RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
        .build();
    when(identityPermissions.myPermissions()).thenAnswer(inv -> new IdentityPermissionsClient.Permissions(null,
        permissionsOf.getOrDefault(TenantContext.userId().orElse(null), List.of())));
    when(users.resolveByPhone(anyString(), anyString()))
        .thenAnswer(inv -> userByPhone.computeIfAbsent(inv.getArgument(0), p -> UuidV7.next()));
  }

  @Test
  void onboardSocietyThenTowersFlatsMembersAndHousehold() throws Exception {
    UUID societyId = onboard("Green Meadows");
    assertThat(outboxCount("society.created", societyId)).isEqualTo(1);

    UUID managerId = UuidV7.next();
    permissionsOf.put(managerId, MANAGER);
    String manager = TestJwtIssuer.token(managerId, societyId, "ESTATE_MANAGER");

    JsonNode settings = put("/v1/society/settings", Map.of("gateApprovalTimeoutSeconds", 300), manager);
    assertThat(settings.path("settings").path("gateApprovalTimeoutSeconds").asInt()).isEqualTo(300);
    assertThat(settings.path("settings").path("billingDueDay").asInt()).isEqualTo(10);

    JsonNode tower = post("/v1/towers", Map.of("name", "Tower A", "code", "A", "floorsCount", 14), manager);
    UUID towerId = id(tower);
    JsonNode dup = post("/v1/towers", Map.of("name", "Again", "code", "a", "floorsCount", 3), manager);
    assertThat(dup.path("code").asString()).isEqualTo("TOWER_CODE_EXISTS");

    UUID flat1203 = id(post("/v1/flats", Map.of("towerId", towerId, "number", "1203", "floor", 12), manager));
    UUID flat101 = id(post("/v1/flats", Map.of("towerId", towerId, "number", "101", "floor", 1), manager));
    JsonNode tooHigh = post("/v1/flats", Map.of("towerId", towerId, "number", "1601", "floor", 16), manager);
    assertThat(tooHigh.path("code").asString()).isEqualTo("INVALID_FLOOR");

    // Family before a holder is refused; the owner makes the flat OCCUPIED
    JsonNode early = post("/v1/members", member(flat1203, "9876500001", "Ravi", "FAMILY", false), manager);
    assertThat(early.path("code").asString()).isEqualTo("NO_FLAT_HOLDER");
    JsonNode owner = post("/v1/members", member(flat1203, "98765 00002", "Asha Verma", "OWNER", true), manager);
    assertThat(owner.path("flatLabel").asString()).isEqualTo("A-1203");
    UUID ownerUserId = userByPhone.get("+919876500002");
    assertThat(owner.path("userId").asString()).isEqualTo(ownerUserId.toString());
    post("/v1/members", member(flat1203, "9876500001", "Ravi Verma", "FAMILY", false), manager);
    JsonNode twice = post("/v1/members", member(flat1203, "9876500002", "Asha", "TENANT", false), manager);
    assertThat(twice.path("code").asString()).isEqualTo("MEMBERSHIP_EXISTS");

    assertThat(get("/v1/flats/" + flat1203, manager).path("status").asString()).isEqualTo("OCCUPIED");
    assertThat(get("/v1/members?flatId=" + flat1203, manager).size()).isEqualTo(2);
    assertThat(outboxCount("society.membership.created", societyId)).isEqualTo(2);
    assertThat(outboxCount("society.flat.updated", societyId)).isEqualTo(1);
    // No phone number ever reaches an event
    assertThat(countRows("select count(*) from outbox_event where payload::text like '%98765%'")).isZero();

    // The owner, as a resident: sees their flat and manages its household, not other flats
    permissionsOf.put(ownerUserId, RESIDENT);
    String resident = TestJwtIssuer.token(ownerUserId, societyId, "RESIDENT_OWNER");
    JsonNode myFlats = get("/v1/me/flats", resident);
    assertThat(myFlats.get(0).path("flatLabel").asString()).isEqualTo("A-1203");

    JsonNode car = post("/v1/vehicles", Map.of("flatId", flat1203, "regNo", "dl 3c ab 1234", "kind", "car"), resident);
    assertThat(car.path("regNo").asString()).isEqualTo("DL3CAB1234");
    JsonNode notMine = post("/v1/vehicles", Map.of("flatId", flat101, "regNo", "HR26X0001", "kind", "BIKE"), resident);
    assertThat(notMine.path("code").asString()).isEqualTo("NOT_YOUR_FLAT");
    JsonNode othersList = get("/v1/vehicles", resident);
    assertThat(othersList.path("code").asString()).isEqualTo("NOT_ALLOWED");
    assertThat(get("/v1/vehicles?flatId=" + flat1203, resident).size()).isEqualTo(1);

    JsonNode maid = post("/v1/domestic-staff", Map.of("name", "Sunita", "kind", "MAID", "phone", "9876511111",
        "flatIds", List.of(flat1203)), resident);
    assertThat(maid.path("phoneMasked").asString()).isEqualTo("98XXXXXX11");
    JsonNode kyc = put("/v1/domestic-staff/" + id(maid),
        Map.of("name", "Sunita", "kind", "MAID", "kycStatus", "VERIFIED"), resident);
    assertThat(kyc.path("code").asString()).isEqualTo("MANAGER_ONLY");
    // The manager registers the same person for another flat: one record, two flats
    JsonNode linked = post("/v1/domestic-staff", Map.of("name", "Sunita Devi", "kind", "MAID",
        "phone", "+919876511111", "flatIds", List.of(flat101)), manager);
    assertThat(linked.path("id").asString()).isEqualTo(maid.path("id").asString());
    assertThat(linked.path("flatIds").size()).isEqualTo(2);
    assertThat(countRows("select count(*) from outbox_event where payload::text like '%9876511111%'")).isZero();

    // Directory is opt-in
    assertThat(get("/v1/directory", resident).size()).isZero();
    put("/v1/me/directory", Map.of("optIn", true), resident);
    JsonNode directory = get("/v1/directory", resident);
    assertThat(directory.get(0).path("name").asString()).isEqualTo("Asha Verma");
    assertThat(directory.get(0).path("flats").get(0).path("flatLabel").asString()).isEqualTo("A-1203");

    // Ending the only holder frees the flat
    post("/v1/members/" + owner.path("membershipId").asString() + "/end", Map.of(), manager);
    assertThat(get("/v1/flats/" + flat1203, manager).path("status").asString()).isEqualTo("VACANT");
    assertThat(outboxCount("society.membership.ended", societyId)).isEqualTo(1);
    assertThat(get("/v1/members?flatId=" + flat1203 + "&includeEnded=true", manager).size()).isEqualTo(2);
  }

  @Test
  void facilitiesLocationsAndParking() throws Exception {
    UUID societyId = onboard("Blue Ridge");
    UUID managerId = UuidV7.next();
    permissionsOf.put(managerId, MANAGER);
    String manager = TestJwtIssuer.token(managerId, societyId, "ESTATE_MANAGER");

    JsonNode gym = post("/v1/facilities", Map.of("kind", "GYM", "name", "Gym", "capacity", 20, "chargeable", false,
        "chargePaise", 0, "bookingRules", Map.of("slotMinutes", 30)), manager);
    assertThat(gym.path("bookingRules").path("slotMinutes").asInt()).isEqualTo(30);
    assertThat(gym.path("bookingRules").path("maxAdvanceDays").asInt()).isEqualTo(14);
    JsonNode badCharge = post("/v1/facilities", Map.of("kind", "HALL", "name", "Hall", "capacity", 100,
        "chargeable", true, "chargePaise", 0), manager);
    assertThat(badCharge.path("code").asString()).isEqualTo("INVALID_CHARGE");
    assertThat(outboxCount("society.facility.created", societyId)).isEqualTo(1);

    UUID basement = id(post("/v1/locations", Map.of("kind", "BASEMENT", "name", "Basement 1"), manager));
    UUID pump = id(post("/v1/locations", Map.of("kind", "PUMP_ROOM", "name", "Pump room", "parentId", basement), manager));
    JsonNode cycle = put("/v1/locations/" + basement,
        Map.of("kind", "BASEMENT", "name", "Basement 1", "parentId", pump), manager);
    assertThat(cycle.path("code").asString()).isEqualTo("LOCATION_CYCLE");

    UUID tower = id(post("/v1/towers", Map.of("name", "T1", "code", "T1", "floorsCount", 4), manager));
    UUID flat = id(post("/v1/flats", Map.of("towerId", tower, "number", "101", "floor", 1), manager));
    UUID slot = id(post("/v1/parking-slots", Map.of("code", "b1-07", "kind", "BASEMENT"), manager));
    JsonNode assigned = put("/v1/parking-slots/" + slot + "/assignment", Map.of("flatId", flat), manager);
    assertThat(assigned.path("code").asString()).isEqualTo("B1-07");
    assertThat(assigned.path("flatLabel").asString()).isEqualTo("T1-101");
    UUID visitor = id(post("/v1/parking-slots", Map.of("code", "V1", "kind", "VISITOR"), manager));
    assertThat(put("/v1/parking-slots/" + visitor + "/assignment", Map.of("flatId", flat), manager)
        .path("code").asString()).isEqualTo("VISITOR_SLOT");
  }

  @Test
  void excelImportValidatesFirstThenCreatesEverything() throws Exception {
    UUID societyId = onboard("Import Heights");
    UUID managerId = UuidV7.next();
    permissionsOf.put(managerId, MANAGER);
    String manager = TestJwtIssuer.token(managerId, societyId, "ESTATE_MANAGER");

    byte[] bad = workbook(List.of("Tower A", "A", "4"), List.of("A", "101", "9", "", ""),
        List.of("A-101", "Asha", "12345", "OWNER", "Y", ""));
    JsonNode failed = awaitImport(upload(bad, false, manager), manager);
    assertThat(failed.path("status").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(failed.path("report").path("errors").size()).isGreaterThanOrEqualTo(2);
    assertThat(get("/v1/towers", manager).size()).isZero();

    byte[] good = new ExcelWorkbooks().template();
    JsonNode dry = awaitImport(upload(good, true, manager), manager);
    assertThat(dry.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(dry.path("report").path("flats").path("created").asInt()).isEqualTo(1);
    assertThat(get("/v1/towers", manager).size()).isZero();

    JsonNode done = awaitImport(upload(good, false, manager), manager);
    assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(done.path("report").path("residents").path("created").asInt()).isEqualTo(1);
    assertThat(get("/v1/flats", manager).get(0).path("status").asString()).isEqualTo("OCCUPIED");

    // Re-running the same file skips what exists; the resident is already a member
    JsonNode again = awaitImport(upload(good, false, manager), manager);
    assertThat(again.path("status").asString()).isEqualTo("COMPLETED_WITH_ERRORS");
    assertThat(again.path("report").path("towers").path("skipped").asInt()).isEqualTo(1);
    assertThat(again.path("report").path("errors").get(0).path("message").asString()).contains("already a member");
  }

  @Test
  void societiesAreIsolated() throws Exception {
    UUID societyA = onboard("Society A");
    UUID societyB = onboard("Society B");
    UUID managerA = UuidV7.next();
    UUID managerB = UuidV7.next();
    permissionsOf.put(managerA, MANAGER);
    permissionsOf.put(managerB, MANAGER);
    String tokenA = TestJwtIssuer.token(managerA, societyA, "ESTATE_MANAGER");
    String tokenB = TestJwtIssuer.token(managerB, societyB, "ESTATE_MANAGER");

    UUID towerA = id(post("/v1/towers", Map.of("name", "A", "code", "A", "floorsCount", 5), tokenA));
    UUID flatA = id(post("/v1/flats", Map.of("towerId", towerA, "number", "101", "floor", 1), tokenA));

    assertThat(get("/v1/flats/" + flatA, tokenB).path("code").asString()).isEqualTo("FLAT_NOT_FOUND");
    assertThat(get("/v1/towers", tokenB).size()).isZero();
    JsonNode crossWrite = post("/v1/flats", Map.of("towerId", towerA, "number", "102", "floor", 1), tokenB);
    assertThat(crossWrite.path("code").asString()).isEqualTo("TOWER_NOT_FOUND");
    assertThat(get("/v1/societies", tokenB).get(0).path("name").asString()).isEqualTo("Society B");
    assertThat(get("/v1/societies", tokenB).size()).isEqualTo(1);

    // A society not in the token is refused before any controller runs
    JsonNode other = http.get().uri("/v1/towers").header("Authorization", "Bearer " + tokenB)
        .header("X-Society-Id", societyA.toString()).retrieve().body(JsonNode.class);
    assertThat(other.path("code").asString()).isEqualTo("SOCIETY_NOT_ALLOWED");

    // Onboarding is platform-only
    int refused = http.post().uri("/v1/societies").header("Authorization", "Bearer " + tokenA)
        .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "X", "city", "Pune", "state", "MH"))
        .exchange((req, res) -> res.getStatusCode().value());
    assertThat(refused).isEqualTo(403);
  }

  // --- helpers ---------------------------------------------------------------------------

  UUID onboard(String name) {
    String admin = TestJwtIssuer.token(UuidV7.next(), null, List.of(), "SUPER_ADMIN");
    JsonNode created = post("/v1/societies", Map.of("name", name, "city", "Gurugram", "state", "Haryana",
        "timezone", "Asia/Kolkata"), admin);
    assertThat(created.path("name").asString()).as(created.toString()).isEqualTo(name);
    return id(created);
  }

  static Map<String, Object> member(UUID flatId, String phone, String name, String kind, boolean primary) {
    return Map.of("flatId", flatId, "phone", phone, "name", name, "kind", kind, "isPrimary", primary);
  }

  JsonNode upload(byte[] file, boolean dryRun, String token) {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("file", new ByteArrayResource(file) {
      @Override
      public String getFilename() {
        return "onboarding.xlsx";
      }
    });
    return http.post().uri("/v1/imports?dryRun=" + dryRun).header("Authorization", "Bearer " + token)
        .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(JsonNode.class);
  }

  JsonNode awaitImport(JsonNode started, String token) throws InterruptedException {
    String id = started.path("id").asString();
    assertThat(id).as(started.toString()).isNotBlank();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(deadline)) {
      JsonNode job = get("/v1/imports/" + id, token);
      if (Set.of("VALIDATION_FAILED", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED")
          .contains(job.path("status").asString())) {
        return job;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("Import " + id + " did not finish");
  }

  static byte[] workbook(List<String> tower, List<String> flat, List<String> resident) throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      sheet(wb, "Towers", List.of("Name", "Code", "Floors"), tower);
      sheet(wb, "Flats", List.of("Tower Code", "Number", "Floor", "Area Sqft", "Type"), flat);
      sheet(wb, "Residents", List.of("Flat Label", "Name", "Phone", "Kind", "Primary", "From Date"), resident);
      wb.write(out);
      return out.toByteArray();
    }
  }

  static void sheet(XSSFWorkbook wb, String name, List<String> header, List<String> row) {
    var s = wb.createSheet(name);
    var h = s.createRow(0);
    var r = s.createRow(1);
    for (int i = 0; i < header.size(); i++) {
      h.createCell(i).setCellValue(header.get(i));
      r.createCell(i).setCellValue(row.get(i));
    }
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

  long outboxCount(String type, UUID societyId) throws Exception {
    return countRows("select count(*) from outbox_event where type = '" + type + "' and society_id = '" + societyId + "'");
  }

  long countRows(String sql) throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
