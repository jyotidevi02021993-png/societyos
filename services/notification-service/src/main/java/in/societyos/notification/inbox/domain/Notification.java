package in.societyos.notification.inbox.domain;

import in.societyos.notification.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * One message to one user. Its in-app copy (title, body in the user's language) is the inbox
 * entry when {@code inApp} is set; {@code params} are kept to render other channels.
 */
@Entity
@Table(name = "notification")
public class Notification extends TenantEntity {

  public record Content(String category, String template, String paramsJson, String priority, String lang,
      String title, String body) {}

  @Column(name = "request_id", nullable = false) private UUID requestId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(nullable = false) private String category;
  @Column(nullable = false) private String template;

  @Column(name = "params", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String paramsJson;

  @Column(nullable = false) private String priority;
  @Column(nullable = false) private String lang;
  @Column(nullable = false) private String title;
  @Column(nullable = false) private String body;
  @Column(name = "in_app", nullable = false) private boolean inApp;
  @Column(name = "read_at") private Instant readAt;

  protected Notification() {}

  public Notification(UUID requestId, UUID userId, Content c, boolean inApp) {
    this.requestId = requestId;
    this.userId = userId;
    this.category = c.category();
    this.template = c.template();
    this.paramsJson = c.paramsJson();
    this.priority = c.priority();
    this.lang = c.lang();
    this.title = c.title();
    this.body = c.body();
    this.inApp = inApp;
  }

  public boolean markRead(Instant now) {
    if (readAt != null) {
      return false;
    }
    readAt = now;
    return true;
  }

  public UUID getRequestId() { return requestId; }
  public UUID getUserId() { return userId; }
  public String getCategory() { return category; }
  public String getTemplate() { return template; }
  public String getParamsJson() { return paramsJson; }
  public String getPriority() { return priority; }
  public String getLang() { return lang; }
  public String getTitle() { return title; }
  public String getBody() { return body; }
  public boolean isInApp() { return inApp; }
  public Instant getReadAt() { return readAt; }
}
