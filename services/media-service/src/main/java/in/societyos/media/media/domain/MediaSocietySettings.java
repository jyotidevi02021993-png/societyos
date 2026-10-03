package in.societyos.media.media.domain;

import in.societyos.media.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Local copy of the society's retention settings (from {@code society.settings.updated}). */
@Entity
@Table(name = "media_society_settings")
public class MediaSocietySettings extends TenantEntity {

  @Column(name = "visitor_retention_days", nullable = false)
  private int visitorRetentionDays;

  protected MediaSocietySettings() {}

  public MediaSocietySettings(int visitorRetentionDays) {
    this.visitorRetentionDays = visitorRetentionDays;
  }

  public int getVisitorRetentionDays() {
    return visitorRetentionDays;
  }

  public void setVisitorRetentionDays(int days) {
    this.visitorRetentionDays = days;
  }
}
