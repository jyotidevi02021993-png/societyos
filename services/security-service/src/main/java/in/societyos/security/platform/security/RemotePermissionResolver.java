package in.societyos.security.platform.security;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Asks identity-service for the caller's permissions (Feign, load-balanced through Eureka) and
 * caches them in Redis under {@code perm:{userId}:{societyId}}; identity evicts the key when
 * roles change.
 */
public class RemotePermissionResolver implements PermissionResolver {

  private static final Logger log = LoggerFactory.getLogger(RemotePermissionResolver.class);

  private final IdentityPermissionsClient identity;
  private final StringRedisTemplate redis;
  private final Duration ttl;

  public RemotePermissionResolver(IdentityPermissionsClient identity, StringRedisTemplate redis, Duration ttl) {
    this.identity = identity;
    this.redis = redis;
    this.ttl = ttl;
  }

  public static String cacheKey(UUID userId, UUID societyId) {
    return "perm:" + userId + ":" + societyId;
  }

  @Override
  public Set<String> permissions(UUID userId, UUID societyId) {
    String key = cacheKey(userId, societyId);
    Set<String> cached = readCache(key);
    if (cached != null && !cached.isEmpty()) {
      return cached;
    }
    IdentityPermissionsClient.Permissions res = identity.myPermissions();
    Set<String> perms = res == null || res.permissions() == null ? Set.of() : Set.copyOf(res.permissions());
    writeCache(key, perms);
    return perms;
  }

  private Set<String> readCache(String key) {
    try {
      return redis.opsForSet().members(key);
    } catch (RuntimeException e) {
      log.debug("Permission cache unavailable: {}", e.getMessage());
      return null;
    }
  }

  private void writeCache(String key, Set<String> perms) {
    if (perms.isEmpty()) {
      return;
    }
    try {
      redis.opsForSet().add(key, perms.toArray(String[]::new));
      redis.expire(key, ttl);
    } catch (RuntimeException e) {
      log.debug("Permission cache unavailable: {}", e.getMessage());
    }
  }
}
