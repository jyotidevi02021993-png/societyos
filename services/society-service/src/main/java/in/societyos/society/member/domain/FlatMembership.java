package in.societyos.society.member.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * A resident's link to a flat as OWNER, TENANT or FAMILY. Memberships are never deleted: ending
 * one sets {@code toDate} and {@code endedAt}, and identity-service revokes the resident role.
 */
@Entity
@Table(name = "flat_membership")
public class FlatMembership extends TenantEntity {

  public static final Set<String> KINDS = Set.of("OWNER", "TENANT", "FAMILY");
  /** Kinds that occupy a flat and can be primary; FAMILY hangs off one of them. */
  public static final Set<String> HOLDER_KINDS = Set.of("OWNER", "TENANT");

  @Column(name = "flat_id", nullable = false, updatable = false)
  private UUID flatId;

  @Column(name = "resident_id", nullable = false, updatable = false)
  private UUID residentId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(nullable = false, updatable = false)
  private String kind;

  @Column(name = "from_date", nullable = false)
  private LocalDate fromDate;

  @Column(name = "to_date")
  private LocalDate toDate;

  @Column(name = "is_primary", nullable = false)
  private boolean primary;

  @Column(name = "ended_at")
  private Instant endedAt;

  protected FlatMembership() {}

  public FlatMembership(UUID flatId, Resident resident, String kind, LocalDate fromDate, boolean primary) {
    super(UuidV7.next());
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("Unknown membership kind " + kind);
    }
    if (primary && !HOLDER_KINDS.contains(kind)) {
      throw new IllegalArgumentException("Only an owner or tenant can be primary");
    }
    this.flatId = flatId;
    this.residentId = resident.getId();
    this.userId = resident.getUserId();
    this.kind = kind;
    this.fromDate = fromDate;
    this.primary = primary;
  }

  public void end(LocalDate on) {
    if (endedAt != null) {
      throw new IllegalStateException("Membership already ended");
    }
    if (on.isBefore(fromDate)) {
      throw new IllegalArgumentException("A membership cannot end before it starts");
    }
    this.toDate = on;
    this.endedAt = Instant.now();
  }

  public boolean isActive() {
    return endedAt == null;
  }

  public boolean isHolder() {
    return HOLDER_KINDS.contains(kind);
  }

  public UUID getFlatId() { return flatId; }
  public UUID getResidentId() { return residentId; }
  public UUID getUserId() { return userId; }
  public String getKind() { return kind; }
  public LocalDate getFromDate() { return fromDate; }
  public LocalDate getToDate() { return toDate; }
  public boolean isPrimary() { return primary; }
  public Instant getEndedAt() { return endedAt; }
}
