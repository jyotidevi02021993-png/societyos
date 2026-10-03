package in.societyos.community.notice.domain;

import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Read receipt: one per (notice, user). */
@Entity
@Table(name = "notice_read")
public class NoticeRead extends TenantEntity {

  @Column(name = "notice_id", nullable = false) private UUID noticeId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(name = "read_at", nullable = false) private Instant readAt;

  protected NoticeRead() {}

  public NoticeRead(UUID noticeId, UUID userId, Instant readAt) {
    this.noticeId = noticeId;
    this.userId = userId;
    this.readAt = readAt;
  }

  public UUID getNoticeId() { return noticeId; }
  public UUID getUserId() { return userId; }
  public Instant getReadAt() { return readAt; }
}
