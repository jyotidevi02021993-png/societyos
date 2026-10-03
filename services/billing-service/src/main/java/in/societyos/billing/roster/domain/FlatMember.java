package in.societyos.billing.roster.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A flat membership (from {@code society.membership.created/ended}); id = membership id. Only the
 * user id and kind are kept: enough to decide whose bills a resident may see, no personal data.
 */
@Entity
@Table(name = "flat_member")
public class FlatMember extends TenantEntity {

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(name = "user_id")
  private UUID userId;

  @Column(nullable = false)
  private String kind;

  @Column(name = "is_primary", nullable = false)
  private boolean primary;

  @Column(name = "ended_at")
  private Instant endedAt;

  protected FlatMember() {}

  public FlatMember(UUID membershipId) {
    super(membershipId);
  }

  public void apply(UUID flatId, UUID userId, String kind, boolean primary) {
    this.flatId = flatId;
    this.userId = userId;
    this.kind = kind == null ? "FAMILY" : kind;
    this.primary = primary;
  }

  public void end(Instant at) {
    if (endedAt == null) {
      endedAt = at;
    }
  }

  public UUID getFlatId() { return flatId; }
  public UUID getUserId() { return userId; }
  public String getKind() { return kind; }
  public boolean isPrimary() { return primary; }
  public Instant getEndedAt() { return endedAt; }
}
