package in.societyos.security.directory.application;

import in.societyos.security.directory.infrastructure.FlatResidentRepository;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.platform.security.PermissionEvaluator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Object-level checks (docs/architecture/05 §3): guards and managers work society-wide; a
 * resident only acts on flats they currently belong to (from the membership copy).
 */
@Component
public class GateAccess {

  private final PermissionEvaluator perm;
  private final FlatResidentRepository residents;

  public GateAccess(PermissionEvaluator perm, FlatResidentRepository residents) {
    this.perm = perm;
    this.residents = residents;
  }

  public UUID userId() {
    return TenantContext.userId()
        .orElseThrow(() -> ProblemException.forbidden("USER_REQUIRED", "A user token is required"));
  }

  public boolean isResidentOf(UUID flatId) {
    UUID userId = TenantContext.userId().orElse(null);
    return userId != null && flatId != null
        && residents.existsBySocietyIdAndFlatIdAndUserIdAndEndedAtIsNull(TenantContext.activeSocietyId(), flatId, userId);
  }

  public List<UUID> myFlatIds() {
    UUID userId = TenantContext.userId().orElse(null);
    if (userId == null) {
      return List.of();
    }
    return residents.findBySocietyIdAndUserIdAndEndedAtIsNull(TenantContext.activeSocietyId(), userId).stream()
        .map(r -> r.getFlatId()).distinct().toList();
  }

  public void requireResidentOf(UUID flatId) {
    if (!isResidentOf(flatId)) {
      throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can only act for your own flat");
    }
  }

  /** Guard desk and managers: the whole society's gate log. */
  public boolean canSeeSocietyLog() {
    return perm.hasAny("gate:log-view", "gate:entry");
  }

  public boolean isGuard() {
    return perm.has("gate:entry");
  }

  public boolean canRecordAttendance() {
    return perm.has("staff:attendance");
  }

  /** Guards and incident handlers respond to SOS alerts. */
  public boolean canRespondToAlerts() {
    return perm.hasAny("gate:entry", "incident:manage");
  }

  /** Residents of the flat (gate:approve) decide; a guard may record a decision taken on the intercom. */
  public void requireCanDecide(UUID flatId) {
    boolean resident = perm.has("gate:approve") && isResidentOf(flatId);
    if (!resident && !isGuard()) {
      throw ProblemException.forbidden("NOT_YOUR_FLAT", "Only residents of the flat can decide on this entry");
    }
  }

  public void requireFlatView(UUID flatId) {
    if (!canSeeSocietyLog() && !isResidentOf(flatId)) {
      throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can only see your own flat");
    }
  }
}
