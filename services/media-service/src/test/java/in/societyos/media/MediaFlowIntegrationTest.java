package in.societyos.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import in.societyos.media.media.application.MediaJobs;
import in.societyos.media.platform.core.UuidV7;
import in.societyos.media.platform.core.tenant.TenantContext;
import in.societyos.media.platform.security.IdentityPermissionsClient;
import in.societyos.media.platform.test.IntegrationTestBase;
import in.societyos.media.platform.test.TestJwtIssuer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import javax.imageio.ImageIO;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Presign → PUT to S3 (Adobe S3Mock container, S3-compatible like MinIO) → complete → scan and
 * thumbnail → signed download, with RLS isolating societies and purpose rules for private files.
 */
class MediaFlowIntegrationTest extends IntegrationTestBase {

  @SuppressWarnings("resource")
  static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:4.7.0")
      .withExposedPorts(9090)
      .waitingFor(Wait.forLogMessage(".*Started S3MockApplication.*", 1)
          .withStartupTimeout(Duration.ofMinutes(3)));

  static {
    S3.start();
  }

  @DynamicPropertySource
  static void s3(DynamicPropertyRegistry r) {
    r.add("sos.media.endpoint", () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(9090));
    r.add("sos.media.access-key", () -> "test");
    r.add("sos.media.secret-key", () -> "test");
    r.add("sos.media.create-bucket", () -> "true");
  }

  static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
  static final Set<String> NOT_SETTABLE = Set.of("host", "content-length");

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired MediaJobs jobs;

  RestClient http;
  final HttpClient raw = HttpClient.newHttpClient();
  final Map<UUID, List<String>> permissionsOf = new ConcurrentHashMap<>();

  record Resp(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http = RestClient.builder().baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {}).build();
    when(identityPermissions.myPermissions()).thenAnswer(inv -> new IdentityPermissionsClient.Permissions(null,
        permissionsOf.getOrDefault(TenantContext.userId().orElse(null), List.of())));
  }

  @Test
  void presignUploadProcessDownloadIsolatedPerSociety() throws Exception {
    UUID societyA = UuidV7.next();
    UUID societyB = UuidV7.next();
    UUID guardId = UuidV7.next();
    UUID residentId = UuidV7.next();
    UUID managerId = UuidV7.next();
    UUID outsiderId = UuidV7.next();
    permissionsOf.put(managerId, List.of("media:manage"));
    String guard = TestJwtIssuer.token(guardId, societyA, "GUARD");
    String resident = TestJwtIssuer.token(residentId, societyA, "RESIDENT_OWNER");
    String manager = TestJwtIssuer.token(managerId, societyA, "ESTATE_MANAGER");
    String outsider = TestJwtIssuer.token(outsiderId, societyB, "ESTATE_MANAGER");

    // 1. Presign a visitor photo, PUT it straight to storage, complete
    byte[] photo = png(800, 400);
    Resp ticket = call(HttpMethod.POST, "/v1/uploads",
        Map.of("purpose", "VISITOR_PHOTO", "contentType", "image/png", "sizeBytes", photo.length,
            "fileName", "gate cam 1.png"), guard);
    assertThat(ticket.status()).as(ticket.body().toString()).isEqualTo(201);
    UUID mediaId = UUID.fromString(ticket.body().path("mediaId").asString());
    assertThat(ticket.body().path("method").asString()).isEqualTo("PUT");

    Resp early = call(HttpMethod.POST, "/v1/uploads/" + mediaId + "/complete", null, guard);
    assertThat(early.body().path("code").asString()).isEqualTo("OBJECT_NOT_UPLOADED");

    assertThat(putTo(ticket.body(), photo)).isBetween(200, 299);
    Resp done = call(HttpMethod.POST, "/v1/uploads/" + mediaId + "/complete", null, guard);
    assertThat(done.body().path("status").asString()).as(done.body().toString()).isEqualTo("UPLOADED");
    assertThat(outboxCount("media.file.uploaded", societyA)).isEqualTo(1);

    // 2. db-scheduler scans and thumbnails it
    JsonNode ready = awaitStatus(mediaId, "READY", guard);
    assertThat(ready.path("scanStatus").asString()).isEqualTo("CLEAN");
    assertThat(ready.path("width").asInt()).isEqualTo(800);
    assertThat(ready.path("thumbnailMediaId").asString()).isNotBlank();
    assertThat(outboxCount("media.file.processed", societyA)).isEqualTo(1);

    // 3. Any member of society A downloads through a signed URL; bytes round-trip
    Resp link = call(HttpMethod.GET, "/v1/media/" + mediaId + "/download-url", null, resident);
    assertThat(link.status()).isEqualTo(200);
    assertThat(fetch(link.body().path("url").asString()).body()).isEqualTo(photo);
    Resp thumb = call(HttpMethod.GET, "/v1/media/" + mediaId + "/download-url?variant=thumbnail", null, resident);
    BufferedImage t = ImageIO.read(new ByteArrayInputStream(fetch(thumb.body().path("url").asString()).body()));
    assertThat(t.getWidth()).isEqualTo(320);
    assertThat(t.getHeight()).isEqualTo(160);

    // 4. Society B cannot see it, and cannot switch into society A
    assertThat(call(HttpMethod.GET, "/v1/media/" + mediaId, null, outsider).status()).isEqualTo(404);
    assertThat(call(HttpMethod.GET, "/v1/media/" + mediaId + "/download-url", null, outsider).status())
        .isEqualTo(404);
    Resp switched = callWithSociety(HttpMethod.GET, "/v1/media/" + mediaId, outsider, societyA);
    assertThat(switched.status()).isEqualTo(403);

    // 5. Private purpose: KYC document readable by its uploader and a media manager only
    byte[] pdf = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);
    Resp kyc = call(HttpMethod.POST, "/v1/uploads",
        Map.of("purpose", "KYC_DOCUMENT", "contentType", "application/pdf", "sizeBytes", pdf.length), resident);
    UUID kycId = UUID.fromString(kyc.body().path("mediaId").asString());
    putTo(kyc.body(), pdf);
    call(HttpMethod.POST, "/v1/uploads/" + kycId + "/complete", null, resident);
    awaitStatus(kycId, "READY", resident);
    assertThat(call(HttpMethod.GET, "/v1/media/" + kycId + "/download-url", null, guard).body().path("code")
        .asString()).isEqualTo("MEDIA_FORBIDDEN");
    assertThat(call(HttpMethod.GET, "/v1/media/" + kycId + "/download-url", null, manager).status()).isEqualTo(200);

    // 6. Limits: wrong type and oversize are refused at presign
    assertThat(call(HttpMethod.POST, "/v1/uploads", Map.of("purpose", "VISITOR_PHOTO",
        "contentType", "application/pdf", "sizeBytes", 10), guard).body().path("code").asString())
        .isEqualTo("CONTENT_TYPE_NOT_ALLOWED");
    assertThat(call(HttpMethod.POST, "/v1/uploads", Map.of("purpose", "VISITOR_PHOTO",
        "contentType", "image/png", "sizeBytes", 50L * 1024 * 1024), guard).body().path("code").asString())
        .isEqualTo("FILE_TOO_LARGE");

    // 7. Virus scan stub rejects EICAR
    byte[] infected = EICAR.getBytes(StandardCharsets.US_ASCII);
    Resp bad = call(HttpMethod.POST, "/v1/uploads",
        Map.of("purpose", "COMPLAINT_PHOTO", "contentType", "image/png", "sizeBytes", infected.length), resident);
    UUID badId = UUID.fromString(bad.body().path("mediaId").asString());
    putTo(bad.body(), infected);
    call(HttpMethod.POST, "/v1/uploads/" + badId + "/complete", null, resident);
    JsonNode rejected = awaitStatus(badId, "REJECTED", resident);
    assertThat(rejected.path("rejectReason").asString()).isEqualTo("VIRUS_DETECTED");
    assertThat(outboxCount("media.file.rejected", societyA)).isEqualTo(1);

    // 8. Delete: not by another member; by the uploader; objects are gone
    String signedUrl = call(HttpMethod.GET, "/v1/media/" + mediaId + "/download-url", null, guard)
        .body().path("url").asString();
    assertThat(call(HttpMethod.DELETE, "/v1/media/" + mediaId, null, resident).status()).isEqualTo(403);
    assertThat(call(HttpMethod.DELETE, "/v1/media/" + mediaId, null, guard).status()).isEqualTo(204);
    assertThat(call(HttpMethod.GET, "/v1/media/" + mediaId, null, guard).status()).isEqualTo(404);
    assertThat(fetch(signedUrl).statusCode()).isEqualTo(404);
    assertThat(outboxCount("media.file.deleted", societyA)).isEqualTo(1);

    // No file names or other personal data in events
    assertThat(countRows("select count(*) from outbox_event where payload::text like '%gate cam%'")).isZero();
  }

  @Test
  void societyRetentionSettingDrivesPurge() throws Exception {
    UUID society = UuidV7.next();
    UUID guardId = UuidV7.next();
    String guard = TestJwtIssuer.token(guardId, society, "GUARD");
    sendEvent("sos.society.events.v1", society, "society.settings.updated",
        Map.of("societyId", society, "settings", Map.of("visitorRetentionDays", 30)));
    Instant deadline = Instant.now().plusSeconds(30);
    while (countRows("select count(*) from media_society_settings where society_id = '" + society
        + "' and visitor_retention_days = 30") == 0 && Instant.now().isBefore(deadline)) {
      Thread.sleep(200);
    }
    byte[] photo = png(40, 40);
    Resp ticket = call(HttpMethod.POST, "/v1/uploads",
        Map.of("purpose", "VISITOR_PHOTO", "contentType", "image/png", "sizeBytes", photo.length), guard);
    UUID id = UUID.fromString(ticket.body().path("mediaId").asString());
    putTo(ticket.body(), photo);
    JsonNode done = call(HttpMethod.POST, "/v1/uploads/" + id + "/complete", null, guard).body();
    Instant uploaded = Instant.parse(done.path("uploadedAt").asString());
    Instant retain = Instant.parse(done.path("retainUntil").asString());
    assertThat(Duration.between(uploaded, retain)).isEqualTo(Duration.ofDays(30));
    awaitStatus(id, "READY", guard);

    execute("update media_file set retain_until = now() - interval '1 minute' where id = '" + id + "'");
    jobs.purge();
    assertThat(call(HttpMethod.GET, "/v1/media/" + id, null, guard).status()).isEqualTo(404);
    assertThat(countRows("select count(*) from media_file where parent_id = '" + id + "' and status = 'DELETED'"))
        .isEqualTo(1);
    assertThat(countRows("select count(*) from outbox_event where type = 'media.file.deleted' and payload::text"
        + " like '%RETENTION%' and society_id = '" + society + "'")).isEqualTo(1);
  }

  // ---- helpers

  JsonNode awaitStatus(UUID id, String status, String token) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(30);
    JsonNode last = null;
    while (Instant.now().isBefore(deadline)) {
      last = call(HttpMethod.GET, "/v1/media/" + id, null, token).body();
      if (status.equals(last.path("status").asString())) {
        return last;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("media " + id + " never reached " + status + ": " + last);
  }

  int putTo(JsonNode ticket, byte[] bytes) throws Exception {
    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(ticket.path("uploadUrl").asString()))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
    for (var e : ticket.path("headers").properties()) {
      if (!NOT_SETTABLE.contains(e.getKey().toLowerCase())) {
        req.header(e.getKey(), e.getValue().asString());
      }
    }
    return raw.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  HttpResponse<byte[]> fetch(String url) throws Exception {
    return raw.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  Resp call(HttpMethod method, String path, Object body, String token) {
    return callWithSociety(method, path, token, null, body);
  }

  Resp callWithSociety(HttpMethod method, String path, String token, UUID society) {
    return callWithSociety(method, path, token, society, null);
  }

  Resp callWithSociety(HttpMethod method, String path, String token, UUID society, Object body) {
    var spec = http.method(method).uri(path).header("Authorization", "Bearer " + token);
    if (society != null) {
      spec = spec.header("X-Society-Id", society.toString());
    }
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    var entity = spec.retrieve().toEntity(String.class);
    String text = entity.getBody();
    JsonNode node = text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
    return new Resp(entity.getStatusCode().value(), node);
  }

  static byte[] png(int w, int h) throws Exception {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(img, "png", out);
      return out.toByteArray();
    }
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

  long outboxCount(String type, UUID societyId) throws Exception {
    return countRows("select count(*) from outbox_event where type = '" + type + "' and society_id = '" + societyId
        + "'");
  }

  long countRows(String sql) throws Exception {
    try (Connection c = owner(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  void execute(String sql) throws Exception {
    try (Connection c = owner(); Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  static Connection owner() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
