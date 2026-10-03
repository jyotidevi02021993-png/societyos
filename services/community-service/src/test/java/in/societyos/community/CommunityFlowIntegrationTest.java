package in.societyos.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import in.societyos.community.platform.core.UuidV7;
import in.societyos.community.platform.core.tenant.TenantContext;
import in.societyos.community.platform.security.IdentityPermissionsClient;
import in.societyos.community.platform.test.IntegrationTestBase;
import in.societyos.community.platform.test.TestJwtIssuer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
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
 * community-service end to end: real Postgres (RLS, app role), Kafka (society events feed the read
 * models) and Redis; identity permissions stubbed per user.
 */
class CommunityFlowIntegrationTest extends IntegrationTestBase {

  static final String SOCIETY_TOPIC = "sos.society.events.v1";
  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  static final List<String> MANAGER = List.of("notice:publish", "notice:view", "poll:create", "event:manage",
      "booking:manage");
  static final List<String> RESIDENT = List.of("notice:view", "poll:vote", "booking:create");

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;
  final Map<UUID, List<String>> permissionsOf = new ConcurrentHashMap<>();

  /** One society with two towers, a flat in each, residents and a hall. */
  record World(UUID society, UUID towerA, UUID towerB, UUID flatA, UUID flatB, UUID residentA, UUID familyA,
      UUID residentB, UUID hall, String manager, String resA, String famA, String resB) {}

  @BeforeEach
  void setUp() {
    http = RestClient.builder().baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();
    when(identityPermissions.myPermissions()).thenAnswer(inv -> new IdentityPermissionsClient.Permissions(null,
        permissionsOf.getOrDefault(TenantContext.userId().orElse(null), List.of())));
  }

  @Test
  void bookingsFollowTheFacilityRules() throws Exception {
    World w = world();
    LocalDate tomorrow = LocalDate.now(IST).plusDays(1);

    JsonNode ok = post("/v1/bookings", booking(w.hall(), w.flatA(), tomorrow, 18, 20, 50), w.resA());
    assertThat(ok.path("status").asString()).as(ok.toString()).isEqualTo("CONFIRMED");
    assertThat(ok.path("chargePaise").asLong()).isEqualTo(100_000); // 2 slots x Rs 500
    assertThat(ok.path("flatLabel").asString()).isEqualTo("A-101");
    UUID bookingId = id(ok);
    String confirmed = outbox("community.booking.confirmed", bookingId);
    assertThat(json.readTree(confirmed).path("data").path("chargePaise").asLong()).isEqualTo(100_000);
    assertThat(outboxCount("community.booking.requested", bookingId)).isEqualTo(1);
    assertThat(countRows("select count(*) from outbox_event where type = 'community.notification.requested' "
        + "and payload::text like '%community.booking.confirmed:" + bookingId + "%'")).isEqualTo(1);

    // Rejected by the rules
    assertThat(post("/v1/bookings", booking(w.hall(), w.flatB(), tomorrow, 19, 21, 10), w.resB())
        .path("code").asString()).isEqualTo("SLOT_TAKEN");
    assertThat(post("/v1/bookings", booking(w.hall(), w.flatA(), tomorrow, 21, 22, 10), w.resA())
        .path("code").asString()).isEqualTo("WEEKLY_LIMIT_REACHED");
    assertThat(post("/v1/bookings", booking(w.hall(), w.flatB(), tomorrow, 21, 22, 500), w.resB())
        .path("code").asString()).isEqualTo("OVER_CAPACITY");
    assertThat(post("/v1/bookings", booking(w.hall(), w.flatB(), LocalDate.now(IST).plusDays(40), 10, 11, 5), w.resB())
        .path("code").asString()).isEqualTo("TOO_FAR_AHEAD");
    assertThat(post("/v1/bookings", booking(w.hall(), w.flatA(), tomorrow, 10, 11, 5), w.resB())
        .path("code").asString()).isEqualTo("NOT_YOUR_FLAT");

    // Availability shows the taken slots
    JsonNode slots = get("/v1/facilities/" + w.hall() + "/availability?date=" + tomorrow, w.resB());
    assertThat(slots.size()).isEqualTo(24);
    assertThat(slots.get(18).path("available").asInt()).isZero();
    assertThat(slots.get(17).path("available").asInt()).isEqualTo(1);

    // Cancel frees the slot; billing learns through community.booking.cancelled
    JsonNode cancelled = post("/v1/bookings/" + bookingId + "/cancel", Map.of("reason", "Plans changed"), w.resA());
    assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(outboxCount("community.booking.cancelled", bookingId)).isEqualTo(1);
    assertThat(post("/v1/bookings", booking(w.hall(), w.flatB(), tomorrow, 19, 21, 10), w.resB())
        .path("status").asString()).isEqualTo("CONFIRMED");
    JsonNode mine = get("/v1/bookings", w.resA());
    assertThat(mine.size()).isEqualTo(1);
    assertThat(mine.get(0).path("status").asString()).isEqualTo("CANCELLED");
    assertThat(get("/v1/bookings", w.manager()).size()).isEqualTo(2);
  }

  @Test
  void pollOneVotePerFlatThenCloseWithResults() throws Exception {
    World w = world();
    JsonNode poll = post("/v1/polls", Map.of("question", "Paint the lobby blue?", "options", List.of("Yes", "No"),
        "oneVotePer", "FLAT"), w.manager());
    UUID pollId = id(poll);
    UUID yes = UUID.fromString(poll.path("options").get(0).path("id").asString());
    UUID no = UUID.fromString(poll.path("options").get(1).path("id").asString());

    JsonNode voted = post("/v1/polls/" + pollId + "/vote", Map.of("flatId", w.flatA(), "optionIds", List.of(yes)),
        w.resA());
    assertThat(voted.path("myChoices").get(0).asString()).as(voted.toString()).isEqualTo(yes.toString());
    assertThat(voted.path("results").isMissingNode() || voted.path("results").isNull()).isTrue(); // hidden while open

    // Same flat, another member: refused. Not the voter's flat: refused. Two options on single choice: refused.
    assertThat(post("/v1/polls/" + pollId + "/vote", Map.of("flatId", w.flatA(), "optionIds", List.of(no)), w.famA())
        .path("code").asString()).isEqualTo("ALREADY_VOTED");
    assertThat(post("/v1/polls/" + pollId + "/vote", Map.of("flatId", w.flatA(), "optionIds", List.of(no)), w.resB())
        .path("code").asString()).isEqualTo("NOT_YOUR_FLAT");
    assertThat(post("/v1/polls/" + pollId + "/vote", Map.of("flatId", w.flatB(), "optionIds", List.of(yes, no)),
        w.resB()).path("code").asString()).isEqualTo("TOO_MANY_CHOICES");
    post("/v1/polls/" + pollId + "/vote", Map.of("flatId", w.flatB(), "optionIds", List.of(no)), w.resB());

    JsonNode closed = post("/v1/polls/" + pollId + "/close", Map.of(), w.manager());
    assertThat(closed.path("status").asString()).isEqualTo("CLOSED");
    assertThat(closed.path("results").get(0).path("votes").asLong()).isEqualTo(1);
    assertThat(closed.path("results").get(1).path("votes").asLong()).isEqualTo(1);
    JsonNode event = json.readTree(outbox("community.poll.closed", pollId));
    assertThat(event.path("data").path("results").size()).isEqualTo(2);
    assertThat(post("/v1/polls/" + pollId + "/vote", Map.of("flatId", w.flatB(), "optionIds", List.of(yes)), w.famA())
        .path("code").asString()).isEqualTo("POLL_CLOSED");
  }

  @Test
  void towerNoticeReadReceiptsAndEvents() throws Exception {
    World w = world();
    JsonNode notice = post("/v1/notices", Map.of("title", "Water shutdown Tower A", "body", "10am to 2pm on Sunday",
        "audience", Map.of("towerIds", List.of(w.towerA())), "attachmentMediaIds", List.of(UuidV7.next())), w.manager());
    UUID noticeId = id(notice);
    assertThat(notice.path("status").asString()).isEqualTo("PUBLISHED");
    assertThat(outboxCount("community.notice.published", noticeId)).isEqualTo(1);
    String request = outboxLike("community.notification.requested", "community.notice:" + noticeId);
    assertThat(request).contains(w.residentA().toString()).contains(w.familyA().toString())
        .doesNotContain(w.residentB().toString());

    // Tower A residents see it; Tower B does not
    assertThat(get("/v1/notices", w.resA()).get(0).path("id").asString()).isEqualTo(noticeId.toString());
    assertThat(get("/v1/notices", w.resB()).size()).isZero();
    assertThat(get("/v1/notices/" + noticeId, w.resB()).path("code").asString()).isEqualTo("NOTICE_NOT_FOUND");

    JsonNode read = post("/v1/notices/" + noticeId + "/read", Map.of(), w.resA());
    assertThat(read.path("read").asBoolean()).isTrue();
    post("/v1/notices/" + noticeId + "/read", Map.of(), w.resA()); // idempotent
    assertThat(get("/v1/notices", w.resA()).get(0).path("read").asBoolean()).isTrue();
    JsonNode receipts = get("/v1/notices/" + noticeId + "/reads", w.manager());
    assertThat(receipts.size()).isEqualTo(1);
    assertThat(receipts.get(0).path("userId").asString()).isEqualTo(w.residentA().toString());
    assertThat(get("/v1/notices/" + noticeId, w.manager()).path("readCount").asLong()).isEqualTo(1);
    assertThat(get("/v1/notices/" + noticeId + "/reads", w.resA()).path("status").asInt()).isEqualTo(403);

    // A scheduled notice is published by the due-work job
    JsonNode later = post("/v1/notices", Map.of("title", "AGM reminder", "body", "Clubhouse 6pm",
        "publishAt", Instant.now().plusSeconds(2).toString()), w.manager());
    assertThat(later.path("status").asString()).isEqualTo("SCHEDULED");
    await(() -> countRows("select count(*) from notice where id = '" + id(later) + "' and status = 'PUBLISHED'") == 1);
    assertThat(get("/v1/notices", w.resB()).size()).isEqualTo(1);

    // Community event with RSVP up to capacity
    JsonNode ev = post("/v1/events", Map.of("kind", "FESTIVAL", "title", "Diwali Mela",
        "startsAt", Instant.now().plus(Duration.ofDays(3)).toString(),
        "endsAt", Instant.now().plus(Duration.ofDays(3)).plus(Duration.ofHours(4)).toString(), "capacity", 5),
        w.manager());
    UUID eventId = id(ev);
    assertThat(outboxCount("community.event.created", eventId)).isEqualTo(1);
    assertThat(put("/v1/events/" + eventId + "/rsvp", Map.of("flatId", w.flatA(), "headcount", 4), w.resA())
        .path("goingHeadcount").asLong()).isEqualTo(4);
    assertThat(put("/v1/events/" + eventId + "/rsvp", Map.of("flatId", w.flatB(), "headcount", 2), w.resB())
        .path("code").asString()).isEqualTo("EVENT_FULL");
    assertThat(put("/v1/events/" + eventId + "/rsvp", Map.of("flatId", w.flatA(), "headcount", 5), w.resA())
        .path("goingHeadcount").asLong()).isEqualTo(5); // changing one's own RSVP
  }

  @Test
  void societiesAreIsolated() throws Exception {
    World a = world();
    World b = world();
    UUID noticeA = id(post("/v1/notices", Map.of("title", "Society A only", "body", "Hello"), a.manager()));
    UUID pollA = id(post("/v1/polls", Map.of("question", "A?", "options", List.of("x", "y")), a.manager()));
    LocalDate tomorrow = LocalDate.now(IST).plusDays(1);
    UUID bookingA = id(post("/v1/bookings", booking(a.hall(), a.flatA(), tomorrow, 10, 11, 5), a.resA()));

    assertThat(get("/v1/notices/" + noticeA, b.manager()).path("code").asString()).isEqualTo("NOTICE_NOT_FOUND");
    assertThat(get("/v1/notices", b.manager()).size()).isZero();
    assertThat(get("/v1/polls/" + pollA, b.manager()).path("code").asString()).isEqualTo("POLL_NOT_FOUND");
    assertThat(post("/v1/bookings/" + bookingA + "/cancel", Map.of(), b.manager()).path("code").asString())
        .isEqualTo("BOOKING_NOT_FOUND");
    assertThat(get("/v1/facilities", b.manager()).size()).isEqualTo(1); // only its own hall
    assertThat(post("/v1/bookings", booking(a.hall(), b.flatA(), tomorrow, 12, 13, 5), b.resA()).path("code")
        .asString()).isEqualTo("FACILITY_NOT_FOUND");
    // A resident of B voting with a flat of A
    assertThat(post("/v1/polls/" + pollA + "/vote", Map.of("flatId", a.flatA(), "optionIds", List.of(UuidV7.next())),
        b.resA()).path("code").asString()).isEqualTo("NOT_YOUR_FLAT");
    // Even as the table owner's view: rows are tagged with their own society
    assertThat(countRows("select count(*) from notice where id = '" + noticeA + "' and society_id = '" + a.society() + "'"))
        .isEqualTo(1);
  }

  // --- world -------------------------------------------------------------------------------

  World world() throws Exception {
    UUID society = UuidV7.next();
    UUID towerA = UuidV7.next();
    UUID towerB = UuidV7.next();
    UUID flatA = UuidV7.next();
    UUID flatB = UuidV7.next();
    UUID residentA = UuidV7.next();
    UUID familyA = UuidV7.next();
    UUID residentB = UuidV7.next();
    UUID managerId = UuidV7.next();
    UUID hall = UuidV7.next();
    sendEvent(society, "society.flat.created", Map.of("flatId", flatA, "towerId", towerA, "label", "A-101"));
    sendEvent(society, "society.flat.created", Map.of("flatId", flatB, "towerId", towerB, "label", "B-201"));
    sendEvent(society, "society.membership.created", membership(flatA, residentA, "OWNER"));
    sendEvent(society, "society.membership.created", membership(flatA, familyA, "FAMILY"));
    sendEvent(society, "society.membership.created", membership(flatB, residentB, "TENANT"));
    sendEvent(society, "society.facility.created", Map.of("facilityId", hall, "kind", "HALL", "name", "Banquet Hall",
        "capacity", 100, "chargeable", true, "chargePaise", 50_000, "status", "ACTIVE",
        "bookingRules", Map.of("slotMinutes", 60, "maxAdvanceDays", 14, "maxPerFlatPerWeek", 1)));
    await(() -> countRows("select count(*) from membership_ref where society_id = '" + society + "'") == 3
        && countRows("select count(*) from facility_ref where id = '" + hall + "'") == 1
        && countRows("select count(*) from flat_ref where society_id = '" + society + "'") == 2);

    permissionsOf.put(managerId, MANAGER);
    permissionsOf.put(residentA, RESIDENT);
    permissionsOf.put(familyA, RESIDENT);
    permissionsOf.put(residentB, RESIDENT);
    return new World(society, towerA, towerB, flatA, flatB, residentA, familyA, residentB, hall,
        TestJwtIssuer.token(managerId, society, "ESTATE_MANAGER"),
        TestJwtIssuer.token(residentA, society, "RESIDENT_OWNER"),
        TestJwtIssuer.token(familyA, society, "RESIDENT_FAMILY"),
        TestJwtIssuer.token(residentB, society, "RESIDENT_TENANT"));
  }

  static Map<String, Object> membership(UUID flat, UUID user, String kind) {
    return Map.of("membershipId", UuidV7.next(), "flatId", flat, "userId", user, "residentId", UuidV7.next(),
        "residentName", "Resident", "kind", kind, "isPrimary", !"FAMILY".equals(kind));
  }

  static Map<String, Object> booking(UUID facility, UUID flat, LocalDate day, int fromHour, int toHour, int guests) {
    return Map.of("facilityId", facility, "flatId", flat,
        "startsAt", day.atTime(fromHour, 0).atZone(IST).toInstant().toString(),
        "endsAt", day.atTime(toHour, 0).atZone(IST).toInstant().toString(), "guests", guests);
  }

  void sendEvent(UUID societyId, String type, Map<String, Object> data) throws Exception {
    UUID id = UuidV7.next();
    Map<String, Object> envelope = Map.of("specversion", "1.0", "id", id, "source", "society-service", "type", type,
        "time", Instant.now().toString(), "subject", "test", "societyid", societyId, "actortype", "SYSTEM",
        "data", data);
    var record = new ProducerRecord<>(SOCIETY_TOPIC, societyId.toString(), json.writeValueAsString(envelope));
    record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
    record.headers().add("ce_id", id.toString().getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get();
  }

  // --- helpers -----------------------------------------------------------------------------

  static UUID id(JsonNode node) {
    String id = node.path("id").asString();
    assertThat(id).as(node.toString()).isNotBlank();
    return UUID.fromString(id);
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

  String outbox(String type, UUID aggregateId) {
    List<String> rows = rows("select payload::text from outbox_event where type = '" + type
        + "' and aggregate_id = '" + aggregateId + "'");
    assertThat(rows).as("outbox %s for %s", type, aggregateId).hasSize(1);
    return rows.getFirst();
  }

  String outboxLike(String type, String fragment) {
    List<String> rows = rows("select payload::text from outbox_event where type = '" + type
        + "' and payload::text like '%" + fragment + "%'");
    assertThat(rows).as("outbox %s with %s", type, fragment).hasSize(1);
    return rows.getFirst();
  }

  long outboxCount(String type, UUID aggregateId) {
    return countRows("select count(*) from outbox_event where type = '" + type + "' and aggregate_id = '"
        + aggregateId + "'");
  }

  static List<String> rows(String sql) {
    List<String> out = new ArrayList<>();
    try (Connection c = owner(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  static long countRows(String sql) {
    try (Connection c = owner(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static Connection owner() throws java.sql.SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  static void await(BooleanSupplier condition) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Condition not met in time");
  }
}
