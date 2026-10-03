package in.societyos.identity.platform.security;

import in.societyos.identity.platform.core.tenant.Tenant;
import in.societyos.identity.platform.core.tenant.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Asks identity-service ({@code GET /v1/me/permissions}) with the caller's own token, so no
 * service credentials are needed, and caches the answer in Redis under
 * {@code perm:{userId}:{societyId}}. Role changes evict the key (identity.role.* events).
 */
public class RemotePermissionResolver implements PermissionResolver {

  private static final Logger log = LoggerFactory.getLogger(RemotePermissionResolver.class);

  private final RestClient identity;
  private final StringRedisTemplate redis;
  private final Duration ttl;

  public RemotePermissionResolver(RestClient identity, StringRedisTemplate redis, Duration ttl) {
    this.identity = identity;
    this.redis = redis;
    this.ttl = ttl;
  }

  record PermissionsResponse(UUID societyId, List<String> permissions) {}

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
    String token = TenantContext.optional().map(Tenant::bearerToken).orElse(null);
    if (token == null) {
      return Set.of();
    }
    PermissionsResponse res =
        identity
            .get()
            .uri("/v1/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(SosClaims.SOCIETY_HEADER, societyId.toString())
            .retrieve()
            .body(PermissionsResponse.class);
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
