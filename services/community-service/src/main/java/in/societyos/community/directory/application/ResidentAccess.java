package in.societyos.community.directory.application;

import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.core.tenant.TenantContext;
import in.societyos.community.platform.security.PermissionEvaluator;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Residents act only for flats they belong to (membership copy from society events); holders of
 * the given manager permission act for any flat.
 */
@Component
public class ResidentAccess {

  private final PermissionEvaluator perm;
  private final DirectoryService directory;

  public ResidentAccess(PermissionEvaluator perm, DirectoryService directory) {
    this.perm = perm;
    this.directory = directory;
  }

  public UUID currentUser() {
    return TenantContext.userId()
        .orElseThrow(() -> ProblemException.forbidden("USER_REQUIRED", "A signed-in user is required"));
  }

  public boolean has(String permission) {
    return perm.has(permission);
  }

  /** Refuses unless the caller holds {@code managerPermission} or lives in the flat. */
  public void requireFlat(UUID flatId, String managerPermission) {
    if (flatId == null) {
      throw ProblemException.badRequest("FLAT_REQUIRED", "flatId is required");
    }
    if (managerPermission != null && perm.has(managerPermission)) {
      return;
    }
    if (!directory.isMemberOf(currentUser(), flatId)) {
      throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can only act for a flat you live in");
    }
  }

  /** The caller must live in the flat, whatever their permissions (voting, RSVP). */
  public void requireOwnFlat(UUID flatId) {
    requireFlat(flatId, null);
  }
}
