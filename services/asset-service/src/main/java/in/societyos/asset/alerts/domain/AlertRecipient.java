package in.societyos.asset.alerts.domain;

import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A user who receives this society's warranty, AMC and PM alerts. */
@Entity
@Table(name = "alert_recipient")
public class AlertRecipient extends TenantEntity {

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  protected AlertRecipient() {}

  public AlertRecipient(UUID userId) {
    super(UuidV7.next());
    this.userId = userId;
  }

  public UUID getUserId() { return userId; }
}
