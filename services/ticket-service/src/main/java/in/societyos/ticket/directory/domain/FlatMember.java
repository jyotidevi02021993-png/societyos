package in.societyos.ticket.directory.domain;

import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of a society-service membership (id = membership id): who lives in which flat. */
@Entity
@Table(name = "flat_member")
public class FlatMember extends TenantEntity {

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;
  @Column(name = "user_id", nullable = false)
  private UUID userId;
  private String kind;
  @Column(nullable = false)
  private boolean active;

  protected FlatMember() {}

  public FlatMember(UUID membershipId, UUID flatId, UUID userId, String kind) {
    super(membershipId);
    this.flatId = flatId;
    this.userId = userId;
    this.kind = kind;
    this.active = true;
  }

  public void end() { this.active = false; }
  public UUID getFlatId() { return flatId; }
  public UUID getUserId() { return userId; }
  public String getKind() { return kind; }
  public boolean isActive() { return active; }
}
