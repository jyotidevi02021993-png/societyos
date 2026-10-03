package in.societyos.security.entry.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import in.societyos.security.common.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * One movement through the gate. Walk-ins go REQUESTED → APPROVED | DENIED | EXPIRED, then an
 * approved visitor is checked IN and later OUT. Pass holders, edge-agent entries and domestic
 * staff are admitted straight to IN.
 */
@Entity
@Table(name = "entry_log")
public class EntryLog extends TenantEntity {

  /** Purposes a guard can raise a walk-in request for. */
  public static final Set<String> VISITOR_PURPOSES = Set.of("GUEST", "CAB", "DELIVERY", "SERVICE");

  public enum Status {
    REQUESTED,
    APPROVED,
    DENIED,
    EXPIRED,
    IN,
    OUT
  }

  @Column(name = "gate_id")
  private UUID gateId;

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(name = "flat_label")
  private String flatLabel;

  @Column(name = "visitor_id")
  private UUID visitorId;

  @Column(name = "visitor_name")
  private String visitorName;

  @Column(name = "staff_id")
  private UUID staffId;

  @Column(nullable = false)
  private String purpose;

  private String company;

  @Column(name = "vehicle_reg")
  private String vehicleReg;

  @Column(name = "photo_media_id")
  private UUID photoMediaId;

  @Column(name = "pass_id")
  private UUID passId;

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private String source = "ONLINE";

  @Column(name = "client_entry_id")
  private String clientEntryId;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "decided_by")
  private UUID decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "in_at")
  private Instant inAt;

  @Column(name = "out_at")
  private Instant outAt;

  @Column(name = "guard_id")
  private UUID guardId;

  protected EntryLog() {}

  /** Who is at the gate, as the guard typed it. */
  public record Arrival(UUID flatId, String flatLabel, UUID gateId, UUID visitorId, String visitorName,
      String purpose, String company, String vehicleReg, UUID photoMediaId, UUID guardId) {}

  private EntryLog(Arrival a, Instant at) {
    this.flatId = a.flatId();
    this.flatLabel = a.flatLabel();
    this.gateId = a.gateId();
    this.visitorId = a.visitorId();
    this.visitorName = a.visitorName();
    this.purpose = a.purpose();
    this.company = blankToNull(a.company());
    this.vehicleReg = blankToNull(a.vehicleReg());
    this.photoMediaId = a.photoMediaId();
    this.guardId = a.guardId();
    this.requestedAt = at.truncatedTo(ChronoUnit.MILLIS);
  }

  /** Walk-in that needs a resident decision within {@code timeout}. */
  public static EntryLog request(Arrival a, Instant now, Duration timeout) {
    if (a.flatId() == null) {
      throw new RuleViolation("FLAT_REQUIRED", "flatId is required");
    }
    if (!VISITOR_PURPOSES.contains(a.purpose())) {
      throw new RuleViolation("INVALID_PURPOSE", "purpose must be one of " + VISITOR_PURPOSES);
    }
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new RuleViolation("INVALID_TIMEOUT", "approval timeout must be positive");
    }
    EntryLog e = new EntryLog(a, now);
    e.status = Status.REQUESTED.name();
    e.expiresAt = e.requestedAt.plus(timeout);
    return e;
  }

  /** Admitted without a resident prompt: a valid gate pass, an edge-agent record, or domestic staff. */
  public static EntryLog admitted(Arrival a, Instant at, String source, UUID passId, UUID staffId) {
    EntryLog e = new EntryLog(a, at);
    e.status = Status.IN.name();
    e.source = source;
    e.passId = passId;
    e.staffId = staffId;
    e.inAt = e.requestedAt;
    return e;
  }

  public void markEdge(String clientEntryId) {
    this.source = "EDGE";
    this.clientEntryId = clientEntryId;
  }

  public void decide(boolean approve, UUID by, Instant now) {
    requireStatus(Status.REQUESTED, "ENTRY_ALREADY_DECIDED", "This entry was already " + status.toLowerCase());
    if (isPastDeadline(now)) {
      throw new RuleViolation("ENTRY_EXPIRED", "The approval window has passed");
    }
    status = (approve ? Status.APPROVED : Status.DENIED).name();
    decidedBy = by;
    decidedAt = now;
  }

  /** True when the request timed out and is now EXPIRED. */
  public boolean expireIfDue(Instant now) {
    if (status() == Status.REQUESTED && isPastDeadline(now)) {
      status = Status.EXPIRED.name();
      decidedAt = now;
      return true;
    }
    return false;
  }

  public void checkIn(Instant now, UUID guard) {
    requireStatus(Status.APPROVED, "ENTRY_NOT_APPROVED", "Only an approved entry can be checked in");
    status = Status.IN.name();
    inAt = now;
    if (guard != null) {
      guardId = guard;
    }
  }

  public void checkOut(Instant now) {
    requireStatus(Status.IN, "ENTRY_NOT_INSIDE", "Only an entry that is inside can be checked out");
    status = Status.OUT.name();
    outAt = now;
  }

  public boolean isPastDeadline(Instant now) {
    return expiresAt != null && !now.isBefore(expiresAt);
  }

  private void requireStatus(Status expected, String code, String message) {
    if (status() != expected) {
      throw new RuleViolation(code, message);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  public Status status() {
    return Status.valueOf(status);
  }

  public UUID getGateId() {
    return gateId;
  }

  public UUID getFlatId() {
    return flatId;
  }

  public String getFlatLabel() {
    return flatLabel;
  }

  public UUID getVisitorId() {
    return visitorId;
  }

  public String getVisitorName() {
    return visitorName;
  }

  public UUID getStaffId() {
    return staffId;
  }

  public String getPurpose() {
    return purpose;
  }

  public String getCompany() {
    return company;
  }

  public String getVehicleReg() {
    return vehicleReg;
  }

  public UUID getPhotoMediaId() {
    return photoMediaId;
  }

  public UUID getPassId() {
    return passId;
  }

  public String getStatus() {
    return status;
  }

  public String getSource() {
    return source;
  }

  public String getClientEntryId() {
    return clientEntryId;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public UUID getDecidedBy() {
    return decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
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
}
