package in.societyos.notification.directory.domain;

import in.societyos.notification.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** The settings this service needs from society-service: timezone and notification retention. */
@Entity
@Table(name = "society_settings")
public class SocietySettings extends TenantEntity {

  public static final int DEFAULT_RETENTION_DAYS = 90;

  private String timezone;
  @Column(name = "notification_retention_days", nullable = false) private int notificationRetentionDays;

  protected SocietySettings() {}

  public SocietySettings(UUID societyId) {
    super(societyId);
    this.notificationRetentionDays = DEFAULT_RETENTION_DAYS;
  }

  public void apply(String timezone, Integer retentionDays) {
    if (timezone != null && !timezone.isBlank()) {
      this.timezone = timezone;
    }
    if (retentionDays != null && retentionDays > 0) {
      this.notificationRetentionDays = retentionDays;
    }
  }

  public String getTimezone() { return timezone; }
  public int getNotificationRetentionDays() { return notificationRetentionDays; }
}
