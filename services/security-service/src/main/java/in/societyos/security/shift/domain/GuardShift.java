package in.societyos.security.shift.domain;

import in.societyos.security.common.RuleViolation;
import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** A guard rostered on a gate; the guard checks in (from 30 minutes before the start) and out. */
@Entity
@Table(name = "guard_shift")
public class GuardShift extends TenantEntity {

  public static final Duration EARLY_CHECK_IN = Duration.ofMinutes(30);
  public static final Duration MAX_LENGTH = Duration.ofHours(24);

  @Column(name = "guard_user_id", nullable = false)
  private UUID guardUserId;

  @Column(name = "gate_id")
  private UUID gateId;

  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  @Column(name = "ends_at", nullable = false)
  private Instant endsAt;

  @Column(name = "checked_in_at")
  private Instant checkedInAt;

  @Column(name = "checked_out_at")
  private Instant checkedOutAt;

  protected GuardShift() {}

  public GuardShift(UUID guardUserId, UUID gateId, Instant startsAt, Instant endsAt) {
    if (guardUserId == null || startsAt == null || endsAt == null) {
      throw new RuleViolation("SHIFT_INCOMPLETE", "guardUserId, startsAt and endsAt are required");
    }
    if (!endsAt.isAfter(startsAt)) {
      throw new RuleViolation("SHIFT_WINDOW", "endsAt must be after startsAt");
    }
    if (Duration.between(startsAt, endsAt).compareTo(MAX_LENGTH) > 0) {
      throw new RuleViolation("SHIFT_TOO_LONG", "A shift can be at most 24 hours");
    }
    this.guardUserId = guardUserId;
    this.gateId = gateId;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
  }

  public void checkIn(UUID userId, Instant now) {
    requireGuard(userId);
    if (checkedInAt != null) {
      throw new RuleViolation("SHIFT_ALREADY_STARTED", "Already checked in to this shift");
    }
    if (now.isBefore(startsAt.minus(EARLY_CHECK_IN)) || !now.isBefore(endsAt)) {
      throw new RuleViolation("SHIFT_NOT_OPEN", "Check-in opens 30 minutes before the shift and closes at its end");
    }
    checkedInAt = now;
  }

  public void checkOut(UUID userId, Instant now) {
    requireGuard(userId);
    if (checkedInAt == null || checkedOutAt != null) {
      throw new RuleViolation("SHIFT_NOT_ACTIVE", "Check in to the shift first");
    }
    checkedOutAt = now;
  }

  public boolean overlaps(Instant from, Instant to) {
    return startsAt.isBefore(to) && endsAt.isAfter(from);
  }

  private void requireGuard(UUID userId) {
    if (!guardUserId.equals(userId)) {
      throw new RuleViolation("NOT_YOUR_SHIFT", "This shift belongs to another guard");
    }
  }

  public UUID getGuardUserId() {
    return guardUserId;
  }

  public UUID getGateId() {
    return gateId;
  }

  public Instant getStartsAt() {
    return startsAt;
  }

  public Instant getEndsAt() {
    return endsAt;
  }

  public Instant getCheckedInAt() {
    return checkedInAt;
  }

  public Instant getCheckedOutAt() {
    return checkedOutAt;
  }
}
