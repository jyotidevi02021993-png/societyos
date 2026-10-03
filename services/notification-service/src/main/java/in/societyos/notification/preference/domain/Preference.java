package in.societyos.notification.preference.domain;

import in.societyos.notification.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** A user's notification settings in one society: quiet hours, language, channels switched off. */
@Entity
@Table(name = "preference")
public class Preference extends TenantEntity {

  @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
  @Column(name = "quiet_start") private LocalTime quietStart;
  @Column(name = "quiet_end") private LocalTime quietEnd;
  private String timezone;
  private String language;

  @Column(name = "disabled", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String disabledJson;

  protected Preference() {}

  public Preference(UUID userId) {
    this.userId = userId;
    this.disabledJson = "{}";
  }

  public void update(LocalTime quietStart, LocalTime quietEnd, String timezone, String language, String disabledJson) {
    this.quietStart = quietStart;
    this.quietEnd = quietEnd;
    this.timezone = timezone;
    this.language = language;
    this.disabledJson = disabledJson;
  }

  public UUID getUserId() { return userId; }
  public LocalTime getQuietStart() { return quietStart; }
  public LocalTime getQuietEnd() { return quietEnd; }
  public String getTimezone() { return timezone; }
  public String getLanguage() { return language; }
  public String getDisabledJson() { return disabledJson; }
}
