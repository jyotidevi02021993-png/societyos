package in.societyos.notification.intake.domain;

import in.societyos.notification.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One accepted {@code *.notification.requested}; UNIQUE(society_id, dedupe_key) drops repeats. */
@Entity
@Table(name = "notification_request")
public class NotificationRequest extends TenantEntity {

  @Column(name = "dedupe_key", nullable = false) private String dedupeKey;
  @Column(name = "source_event_id") private UUID sourceEventId;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(nullable = false) private String category;
  @Column(nullable = false) private String template;
  @Column(nullable = false) private String priority;
  @Column(nullable = false) private int recipients;

  protected NotificationRequest() {}

  public UUID getSourceEventId() { return sourceEventId; }
  public String getDedupeKey() { return dedupeKey; }
  public String getSourceType() { return sourceType; }
  public String getCategory() { return category; }
  public String getTemplate() { return template; }
  public String getPriority() { return priority; }
  public int getRecipients() { return recipients; }
}
