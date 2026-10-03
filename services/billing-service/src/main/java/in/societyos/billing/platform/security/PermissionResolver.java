package in.societyos.billing.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves the {@code module:action} permissions a user holds in one society. identity-service
 * answers from its database; every other service asks identity-service and caches in Redis.
 */
public interface PermissionResolver {

  /** Grants everything; held by SUPER_ADMIN only for platform-level actions. */
  String ALL = "*";

  Set<String> permissions(UUID userId, UUID societyId);
}
