package in.societyos.realtime.push.application;

import in.societyos.realtime.platform.events.CloudEvent;
import in.societyos.realtime.push.domain.Delivery;
import in.societyos.realtime.push.domain.PushRouter;
import in.societyos.realtime.push.infrastructure.RedisFanout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns security events into socket pushes. The pushed frame is the event itself
 * ({@code {id, type, societyId, time, data}}); events carry no phone numbers, so nothing is
 * redacted here. Entry audiences are remembered for an hour so follow-ups reach the same residents.
 */
@Service
public class PushService {

  private static final Logger log = LoggerFactory.getLogger(PushService.class);
  private static final Duration AUDIENCE_TTL = Duration.ofHours(1);

  private final RedisFanout fanout;
  private final StringRedisTemplate redis;
  private final JsonMapper json;

  public PushService(RedisFanout fanout, StringRedisTemplate redis, JsonMapper json) {
    this.fanout = fanout;
    this.redis = redis;
    this.json = json;
  }

  public List<Delivery> push(CloudEvent<JsonNode> event) {
    JsonNode data = event.data();
    UUID societyId = event.societyId();
    String entryId = data == null ? "" : data.path("entryId").asString();
    List<UUID> residents = List.of();
    if ("security.entry.requested".equals(event.type())) {
      residents = uuids(data.path("residentUserIds"));
      remember(societyId, entryId, residents);
    } else if (PushRouter.ENTRY_FOLLOW_UPS.contains(event.type())) {
      residents = recall(societyId, entryId);
    }
    List<Delivery> deliveries = PushRouter.route(event.type(), societyId, residents);
    if (deliveries.isEmpty()) {
      return deliveries;
    }
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("id", event.id());
    frame.put("type", event.type());
    frame.put("societyId", societyId);
    frame.put("time", event.time() == null ? null : event.time().toString());
    frame.put("data", data);
    String payload = json.writeValueAsString(frame);
    deliveries.forEach(d -> fanout.publish(d, payload));
    log.debug("Pushed {} to {} destination(s)", event.type(), deliveries.size());
    return deliveries;
  }

  private static List<UUID> uuids(JsonNode array) {
    List<UUID> out = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(n -> {
        try {
          out.add(UUID.fromString(n.asString()));
        } catch (IllegalArgumentException ignored) {
          // skip malformed ids
        }
      });
    }
    return out;
  }

  private static String audienceKey(UUID societyId, String entryId) {
    return "rt:entry:" + societyId + ":" + entryId;
  }

  private void remember(UUID societyId, String entryId, List<UUID> residents) {
    if (entryId.isEmpty() || residents.isEmpty()) {
      return;
    }
    try {
      redis.opsForValue().set(audienceKey(societyId, entryId),
          residents.stream().map(UUID::toString).collect(Collectors.joining(",")), AUDIENCE_TTL);
    } catch (RuntimeException e) {
      log.warn("Audience cache unavailable: {}", e.getMessage());
    }
  }

  private List<UUID> recall(UUID societyId, String entryId) {
    if (entryId.isEmpty()) {
      return List.of();
    }
    try {
      String v = redis.opsForValue().get(audienceKey(societyId, entryId));
      return v == null || v.isBlank() ? List.of() : Arrays.stream(v.split(",")).map(UUID::fromString).toList();
    } catch (RuntimeException e) {
      log.warn("Audience cache unavailable: {}", e.getMessage());
      return List.of();
    }
  }
}
