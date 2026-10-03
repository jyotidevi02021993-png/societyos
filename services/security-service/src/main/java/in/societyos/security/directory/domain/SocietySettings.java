package in.societyos.security.directory.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Duration;
import java.util.UUID;

/** The gate-relevant part of a society's settings (from {@code society.settings.updated}); id = society id. */
@Entity
@Table(name = "society_settings")
public class SocietySettings extends TenantEntity {

  @Column(name = "gate_approval_timeout_seconds", nullable = false)
  private int gateApprovalTimeoutSeconds;

  @Column(name = "visitor_retention_days", nullable = false)
  private int visitorRetentionDays;

  protected SocietySettings() {}

  public SocietySettings(UUID societyId, int gateApprovalTimeoutSeconds, int visitorRetentionDays) {
    super(societyId);
    update(gateApprovalTimeoutSeconds, visitorRetentionDays);
  }

  /** Clamps to the database limits, so a bad value upstream cannot poison the consumer. */
  public void update(Integer gateApprovalTimeoutSeconds, Integer visitorRetentionDays) {
    if (gateApprovalTimeoutSeconds != null) {
      this.gateApprovalTimeoutSeconds = Math.clamp(gateApprovalTimeoutSeconds, 10, 3600);
    }
    if (visitorRetentionDays != null) {
      this.visitorRetentionDays = Math.clamp(visitorRetentionDays, 1, 3650);
    }
  }

  public Duration approvalTimeout() {
    return Duration.ofSeconds(gateApprovalTimeoutSeconds);
  }

  public int getVisitorRetentionDays() {
    return visitorRetentionDays;
  }
}
