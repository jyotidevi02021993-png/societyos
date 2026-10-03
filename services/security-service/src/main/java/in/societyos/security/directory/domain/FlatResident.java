package in.societyos.security.directory.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A flat membership (from {@code society.membership.created/ended}); id = membership id. */
@Entity
@Table(name = "flat_resident")
public class FlatResident extends TenantEntity {

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "resident_id")
  private UUID residentId;

  @Column(name = "resident_name")
  private String residentName;

  @Column(nullable = false)
  private String kind;

  @Column(name = "is_primary", nullable = false)
  private boolean primary;

  @Column(name = "ended_at")
  private Instant endedAt;

  protected FlatResident() {}

  public FlatResident(UUID membershipId) {
    super(membershipId);
  }

  public void apply(UUID flatId, UUID userId, UUID residentId, String residentName, String kind, boolean primary) {
    this.flatId = flatId;
    this.userId = userId;
    this.residentId = residentId;
    this.residentName = residentName;
    this.kind = kind == null ? "FAMILY" : kind;
    this.primary = primary;
  }

  public void end(Instant at) {
    if (endedAt == null) {
      endedAt = at;
    }
  }

  public boolean isActive() {
    return endedAt == null;
  }

  public UUID getFlatId() {
    return flatId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getResidentId() {
    return residentId;
  }

  public String getResidentName() {
    return residentName;
  }

  public String getKind() {
    return kind;
  }

  public boolean isPrimary() {
    return primary;
  }

  public Instant getEndedAt() {
    return endedAt;
  }
}
