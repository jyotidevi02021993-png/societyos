package in.societyos.asset.platform.security;

import in.societyos.asset.platform.core.tenant.Tenant;
import in.societyos.asset.platform.core.tenant.TenantContext;
import java.util.Set;

/**
 * Exposed as bean {@code perm} for method security:
 *
 * <pre>{@code @PreAuthorize("@perm.has('jobcard:approve')")}</pre>
 *
 * Checks the active society only; multi-site reads use {@code sids} + RLS, never this check.
 */
public class PermissionEvaluator {

  private final PermissionResolver resolver;

  public PermissionEvaluator(PermissionResolver resolver) {
    this.resolver = resolver;
  }

  public boolean has(String permission) {
    Tenant t = TenantContext.optional().orElse(null);
    if (t == null || t.userId() == null) {
      return false;
    }
    if (t.actorType() == Tenant.ActorType.SERVICE) {
      return true; // service tokens are audience-restricted at issue time
    }
    if (t.activeSocietyId() == null) {
      return t.hasRole("SUPER_ADMIN") && permission.startsWith("platform:");
    }
    Set<String> perms = resolver.permissions(t.userId(), t.activeSocietyId());
    return perms.contains(permission) || perms.contains(PermissionResolver.ALL) || perms.contains(module(permission) + ":*");
  }

  public boolean hasAny(String... permissions) {
    for (String p : permissions) {
      if (has(p)) {
        return true;
      }
    }
    return false;
  }

  private static String module(String permission) {
    int i = permission.indexOf(':');
    return i < 0 ? permission : permission.substring(0, i);
  }
}
