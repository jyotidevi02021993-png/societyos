package in.societyos.realtime.socket.infrastructure;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** {@code presence:{userId}} → pod id, 90 s TTL refreshed every 60 s (docs/architecture/04 use 8). */
@Component
public class PresenceStore {

  private static final Logger log = LoggerFactory.getLogger(PresenceStore.class);
  static final Duration TTL = Duration.ofSeconds(90);

  private final StringRedisTemplate redis;

  public PresenceStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void online(UUID userId, String podId) {
    try {
      redis.opsForValue().set("presence:" + userId, podId, TTL);
    } catch (RuntimeException e) {
      log.debug("Presence unavailable: {}", e.getMessage());
    }
  }

  public void refresh(Collection<UUID> userIds, String podId) {
    userIds.forEach(u -> online(u, podId));
  }

  public void offline(UUID userId) {
    try {
      redis.delete("presence:" + userId);
    } catch (RuntimeException e) {
      log.debug("Presence unavailable: {}", e.getMessage());
    }
  }
}
