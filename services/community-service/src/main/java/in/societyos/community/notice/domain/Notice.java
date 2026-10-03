package in.societyos.community.notice.domain;

import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A notice on the society board. SCHEDULED until {@code publishAt}, then PUBLISHED (visible to its
 * audience until {@code expiresAt}); WITHDRAWN hides it again. Attachments are media ids.
 */
@Entity
@Table(name = "notice")
public class Notice extends TenantEntity {

  public record Details(String title, String body, boolean pinned, Instant publishAt, Instant expiresAt) {}

  @Column(nullable = false) private String title;
  @Column(nullable = false) private String body;

  @Column(name = "audience", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String audienceJson;

  @Column(name = "attachments", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String attachmentsJson;

  @Column(nullable = false) private boolean pinned;
  @Column(name = "publish_at", nullable = false) private Instant publishAt;
  @Column(name = "expires_at") private Instant expiresAt;
  @Column(nullable = false) private String status;
  @Column(name = "published_at") private Instant publishedAt;

  protected Notice() {}

  public Notice(Details d, String audienceJson, String attachmentsJson) {
    this.title = d.title();
    this.body = d.body();
    this.pinned = d.pinned();
    this.publishAt = d.publishAt();
    this.expiresAt = d.expiresAt();
    this.audienceJson = audienceJson;
    this.attachmentsJson = attachmentsJson;
    this.status = "SCHEDULED";
  }

  /** Moves a due notice to PUBLISHED; returns false if it was not scheduled or not yet due. */
  public boolean publishIfDue(Instant now) {
    if (!"SCHEDULED".equals(status) || publishAt.isAfter(now)) {
      return false;
    }
    status = "PUBLISHED";
    publishedAt = now;
    return true;
  }

  public void withdraw() {
    if ("WITHDRAWN".equals(status)) {
      throw ProblemException.unprocessable("NOTICE_WITHDRAWN", "The notice is already withdrawn");
    }
    status = "WITHDRAWN";
  }

  public boolean isVisibleAt(Instant now) {
    return "PUBLISHED".equals(status) && (expiresAt == null || expiresAt.isAfter(now));
  }

  public String getTitle() { return title; }
  public String getBody() { return body; }
  public String getAudienceJson() { return audienceJson; }
  public String getAttachmentsJson() { return attachmentsJson; }
  public boolean isPinned() { return pinned; }
  public Instant getPublishAt() { return publishAt; }
  public Instant getExpiresAt() { return expiresAt; }
  public String getStatus() { return status; }
  public Instant getPublishedAt() { return publishedAt; }
}
