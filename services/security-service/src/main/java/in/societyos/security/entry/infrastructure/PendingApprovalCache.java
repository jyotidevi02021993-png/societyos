package in.societyos.security.entry.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code gate:pending:{societyId}:{entryId}} → expiresAt (docs/architecture/04 use 9): lets the
 * guard console show live countdowns without polling the database. Disposable: Postgres is the
 * truth, and a Redis failure never fails a gate request.
 */
@Component
public class PendingApprovalCache {

  private static final Logger log = LoggerFactory.getLogger(PendingApprovalCache.class);
  private static final Duration MAX_TTL = Duration.ofMinutes(10);

  private final StringRedisTemplate redis;

  public PendingApprovalCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  static String key(UUID societyId, UUID entryId) {
    return "gate:pending:" + societyId + ":" + entryId;
  }

  public void put(UUID societyId, UUID entryId, Instant expiresAt) {
    try {
      Duration ttl = Duration.between(Instant.now(), expiresAt);
      if (ttl.isNegative() || ttl.isZero()) {
        return;
      }
      redis.opsForValue().set(key(societyId, entryId), expiresAt.toString(), ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl);
    } catch (RuntimeException e) {
      log.warn("Pending-approval cache unavailable: {}", e.getMessage());
    }
  }

  public void remove(UUID societyId, UUID entryId) {
    try {
      redis.delete(key(societyId, entryId));
    } catch (RuntimeException e) {
      log.warn("Pending-approval cache unavailable: {}", e.getMessage());
    }
  }
}
