package in.societyos.identity.role.application;

import in.societyos.identity.user.infrastructure.AppUserRepository;
import in.societyos.identity.platform.security.PermissionResolver;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * identity-service answers permission checks from its own database (other services use the
 * remote resolver). A platform admin working inside a society gets onboarding permissions, but
 * never finance permissions (docs/architecture/05 §3: SUPER_ADMIN cannot edit financial records).
 */
@Component
class LocalPermissionResolver implements PermissionResolver {

  static final Set<String> PLATFORM_ADMIN_IN_SOCIETY =
      Set.of(
          "society:view", "society:manage", "member:manage", "member:view", "import:run", "role:manage",
          "user:manage", "settings:manage", "audit:view", "dashboard:view");

  private final RoleService roles;
  private final AppUserRepository users;

  LocalPermissionResolver(RoleService roles, AppUserRepository users) {
    this.roles = roles;
    this.users = users;
  }

  @Override
  public Set<String> permissions(UUID userId, UUID societyId) {
    Set<String> result = new TreeSet<>(roles.permissionsOf(userId, societyId));
    users.findById(userId)
        .filter(u -> u.isPlatformAdmin())
        .ifPresent(u -> result.addAll(PLATFORM_ADMIN_IN_SOCIETY));
    return result;
  }
}
