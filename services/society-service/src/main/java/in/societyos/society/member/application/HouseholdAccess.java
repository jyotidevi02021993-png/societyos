package in.societyos.society.member.application;

import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.core.tenant.TenantContext;
import in.societyos.society.platform.security.PermissionEvaluator;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may change a flat's vehicles and domestic staff: managers ({@code member:manage}) for any
 * flat, residents ({@code household:manage}) only for flats they own or rent.
 */
@Component
public class HouseholdAccess {

  private final PermissionEvaluator perm;
  private final MemberService members;

  public HouseholdAccess(PermissionEvaluator perm, MemberService members) {
    this.perm = perm;
    this.members = members;
  }

  public boolean isManager() {
    return perm.has("member:manage");
  }

  public void requireFlat(UUID flatId) {
    if (isManager()) {
      return;
    }
    UUID userId = TenantContext.userId().orElse(null);
    if (userId == null || !perm.has("household:manage") || !members.isHolderOf(userId, flatId)) {
      throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can only manage your own flat");
    }
  }

  public void requireFlats(Collection<UUID> flatIds) {
    flatIds.forEach(this::requireFlat);
  }

  /** Society-wide household lists: managers, member viewers and guards at the gate. */
  public boolean canReadAll() {
    return perm.hasAny("member:manage", "member:view", "gate:entry");
  }

  public void requireReadAll() {
    if (!canReadAll()) {
      throw ProblemException.forbidden("NOT_ALLOWED", "Pass a flatId to see your own flat");
    }
  }

  /** One flat's household: anyone who can read all, or an owner/tenant of that flat. */
  public void requireFlatRead(UUID flatId) {
    if (canReadAll()) {
      return;
    }
    UUID userId = TenantContext.userId().orElse(null);
    if (userId == null || !members.isHolderOf(userId, flatId)) {
      throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can only see your own flat");
    }
  }
}
