package in.societyos.community.directory.domain;

import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Who lives in which flat, from {@code society.membership.created/ended}. Residents may vote, RSVP
 * and book only for flats with an active membership here. No names or phones are kept.
 */
@Entity
@Table(name = "membership_ref")
public class MembershipRef extends TenantEntity {

  @Column(name = "flat_id", nullable = false) private UUID flatId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(nullable = false) private String kind;
  @Column(nullable = false) private boolean active;

  protected MembershipRef() {}

  public MembershipRef(UUID membershipId, UUID flatId, UUID userId, String kind) {
    super(membershipId);
    this.flatId = flatId;
    this.userId = userId;
    this.kind = kind == null ? "OWNER" : kind;
    this.active = true;
  }

  public void end() { this.active = false; }
  public void reactivate() { this.active = true; }

  public UUID getFlatId() { return flatId; }
  public UUID getUserId() { return userId; }
  public String getKind() { return kind; }
  public boolean isActive() { return active; }
}
