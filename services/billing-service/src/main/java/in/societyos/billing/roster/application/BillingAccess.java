package in.societyos.billing.roster.application;

import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.platform.core.tenant.TenantContext;
import in.societyos.billing.platform.security.PermissionEvaluator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may see or pay which flat's money: {@code bill:view} / {@code payment:view} holders see every
 * flat; residents ({@code bill:view-own}, {@code bill:pay}) only the flats they are an active member
 * of, checked against the local membership copies.
 */
@Component
public class BillingAccess {

  private final PermissionEvaluator perm;
  private final RosterService roster;

  public BillingAccess(PermissionEvaluator perm, RosterService roster) {
    this.perm = perm;
    this.roster = roster;
  }

  public boolean canViewAllBills() {
    return perm.has("bill:view");
  }

  public boolean canViewAllPayments() {
    return perm.hasAny("payment:view", "payment:record", "bill:view");
  }

  /** Flats the caller may read when not a viewer of all: their own. */
  public List<UUID> ownFlats() {
    UUID userId = TenantContext.userId().orElse(null);
    if (userId == null || !perm.hasAny("bill:view-own", "bill:pay")) {
      return List.of();
    }
    return roster.flatIdsOf(userId);
  }

  public void requireFlatRead(UUID flatId) {
    if (canViewAllBills()) {
      return;
    }
    requireOwn(flatId, "bill:view-own");
  }

  public void requirePaymentRead(UUID flatId) {
    if (canViewAllPayments()) {
      return;
    }
    requireOwn(flatId, "bill:view-own");
  }

  /** Online payment of a bill: a member of the flat with {@code bill:pay}. */
  public void requirePay(UUID flatId) {
    requireOwn(flatId, "bill:pay");
  }

  private void requireOwn(UUID flatId, String permission) {
    UUID userId = TenantContext.userId().orElse(null);
    if (userId == null || !perm.has(permission) || !roster.isMemberOf(userId, flatId)) {
      throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can only see or pay your own flat's bills");
    }
  }
}
