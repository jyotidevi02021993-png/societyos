package in.societyos.security.attendance.domain;

import in.societyos.security.common.RuleViolation;
import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** One visit of a domestic staff member: in at the gate, out later. At most one open visit each. */
@Entity
@Table(name = "staff_attendance")
public class StaffAttendance extends TenantEntity {

  @Column(name = "staff_id", nullable = false)
  private UUID staffId;

  @Column(name = "gate_id")
  private UUID gateId;

  @Column(name = "in_at", nullable = false)
  private Instant inAt;

  @Column(name = "out_at")
  private Instant outAt;

  @Column(name = "guard_id")
  private UUID guardId;

  @Column(name = "entry_id")
  private UUID entryId;

  protected StaffAttendance() {}

  public StaffAttendance(UUID staffId, UUID gateId, Instant inAt, UUID guardId, UUID entryId) {
    this.staffId = staffId;
    this.gateId = gateId;
    this.inAt = inAt;
    this.guardId = guardId;
    this.entryId = entryId;
  }

  /** Staff who are blocked by the society are turned away at the gate. */
  public static void requireAllowed(String status) {
    if ("BLOCKED".equals(status)) {
      throw new RuleViolation("STAFF_BLOCKED", "This staff member is blocked by the society");
    }
  }

  public void checkOut(Instant at) {
    if (outAt != null) {
      throw new RuleViolation("ALREADY_OUT", "This visit is already closed");
    }
    outAt = at.isBefore(inAt) ? inAt : at;
  }

  public boolean isOpen() {
    return outAt == null;
  }

  public Duration duration(Instant now) {
    return Duration.between(inAt, outAt == null ? now : outAt);
  }

  public UUID getStaffId() {
    return staffId;
  }

  public UUID getGateId() {
    return gateId;
  }

  public Instant getInAt() {
    return inAt;
  }

  public Instant getOutAt() {
    return outAt;
  }

  public UUID getGuardId() {
    return guardId;
  }

  public UUID getEntryId() {
    return entryId;
  }
}
