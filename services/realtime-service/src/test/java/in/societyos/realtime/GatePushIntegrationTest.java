package in.societyos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.realtime.platform.test.IntegrationTestBase;
import in.societyos.realtime.platform.test.TestJwtIssuer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Security events from Kafka reach the right sockets through Redis; subscriptions are authorised. */
class GatePushIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JsonMapper json;

  WebSocketStompClient client;
  UUID society;
  UUID resident;
  UUID guard;

  @BeforeEach
  void setUp() {
    client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new SimpleMessageConverter());
    society = UUID.randomUUID();
    resident = UUID.randomUUID();
    guard = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    client.stop();
  }

  @Test
  void entryRequestReachesResidentAndGuardAndTheDecisionFollows() throws Exception {
    givenPermissions("gate:entry", "gate:log-view");
    StompSession guardSession = connect(TestJwtIssuer.token(guard, society, "GUARD"));
    BlockingQueue<JsonNode> console = subscribe(guardSession, "/topic/society." + society + ".gate");
    StompSession residentSession = connect(TestJwtIssuer.token(resident, society, "RESIDENT_OWNER"));
    BlockingQueue<JsonNode> mine = subscribe(residentSession, "/user/queue/gate");
    Thread.sleep(500); // subscriptions registered

    UUID entryId = UUID.randomUUID();
    send("security.entry.requested", Map.of("entryId", entryId, "flatId", UUID.randomUUID(), "flatLabel", "A-1203",
        "visitorName", "Ramesh", "purpose", "DELIVERY", "guardUserId", guard, "residentUserIds", List.of(resident),
        "expiresAt", Instant.now().plusSeconds(120).toString()));

    JsonNode prompt = mine.poll(30, TimeUnit.SECONDS);
    assertThat(prompt).as("resident push").isNotNull();
    assertThat(prompt.path("type").asString()).isEqualTo("security.entry.requested");
    assertThat(prompt.path("data").path("visitorName").asString()).isEqualTo("Ramesh");
    assertThat(console.poll(10, TimeUnit.SECONDS)).as("guard console").isNotNull();

    send("security.entry.approved", Map.of("entryId", entryId, "flatId", UUID.randomUUID(), "decidedBy", resident,
        "decidedAt", Instant.now().toString()));
    JsonNode approved = console.poll(30, TimeUnit.SECONDS);
    assertThat(approved).isNotNull();
    assertThat(approved.path("type").asString()).isEqualTo("security.entry.approved");
    JsonNode dismissed = mine.poll(10, TimeUnit.SECONDS);
    assertThat(dismissed).as("residents learn the entry was decided").isNotNull();
    assertThat(dismissed.path("data").path("entryId").asString()).isEqualTo(entryId.toString());
  }

  @Test
  void anotherSocietysTopicAndMissingTokensAreRefused() throws Exception {
    givenPermissions("gate:entry");
    UUID other = UUID.randomUUID();
    StompSession guardSession = connect(TestJwtIssuer.token(guard, society, "GUARD"));
    BlockingQueue<JsonNode> foreign = subscribe(guardSession, "/topic/society." + other + ".gate");
    Thread.sleep(500);
    send(other, "security.sos.raised", Map.of("sosId", UUID.randomUUID(), "kind", "FIRE", "raisedBy", guard,
        "at", Instant.now().toString()));
    assertThat(foreign.poll(5, TimeUnit.SECONDS)).isNull();

    // Without permission for the screen, the society topic is refused too.
    givenPermissions("gatepass:view");
    UUID viewer = UUID.randomUUID();
    StompSession residentSession = connect(TestJwtIssuer.token(viewer, society, "RESIDENT_OWNER"));
    BlockingQueue<JsonNode> alerts = subscribe(residentSession, "/topic/society." + society + ".alerts");
    Thread.sleep(500);
    send(society, "security.incident.reported", Map.of("incidentId", UUID.randomUUID(), "kind", "TRESPASS",
        "severity", "HIGH", "reportedBy", guard));
    assertThat(alerts.poll(5, TimeUnit.SECONDS)).isNull();

    // CONNECT without a token gets an ERROR frame instead of CONNECTED.
    assertThatThrownBy(() -> client.connectAsync(url(), new WebSocketHttpHeaders(), new StompHeaders(),
        new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private String url() {
    return "ws://localhost:" + port + "/ws";
  }

  private StompSession connect(String token) throws Exception {
    StompHeaders connect = new StompHeaders();
    connect.add("Authorization", "Bearer " + token);
    return client.connectAsync(url(), new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() {})
        .get(10, TimeUnit.SECONDS);
  }

  private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
    BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
    session.subscribe(destination, handler(queue));
    return queue;
  }

  private StompFrameHandler handler(BlockingQueue<JsonNode> queue) {
    return new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return byte[].class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        queue.add(json.readTree(new String((byte[]) payload, StandardCharsets.UTF_8)));
      }
    };
  }

  private void send(String type, Map<String, Object> data) throws Exception {
    send(society, type, data);
  }

  private void send(UUID societyId, String type, Map<String, Object> data) throws Exception {
    UUID id = UUID.randomUUID();
    Map<String, Object> envelope = Map.of("specversion", "1.0", "id", id, "source", "security-service", "type", type,
        "time", Instant.now().toString(), "subject", "test", "societyid", societyId, "actortype", "USER", "data", data);
    var record = new ProducerRecord<>("sos.security.events.v1", societyId.toString(), json.writeValueAsString(envelope));
    record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
    record.headers().add("ce_id", id.toString().getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get();
  }
}
