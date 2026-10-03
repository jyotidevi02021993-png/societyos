package in.societyos.notification.delivery.domain;

import in.societyos.notification.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One channel of one notification: PENDING (due at {@code nextAttemptAt}, possibly held by quiet
 * hours) → SENT, or FAILED after the retry budget, or SKIPPED (preference, no device, no contact).
 */
@Entity
@Table(name = "delivery")
public class Delivery extends TenantEntity {

  @Column(name = "notification_id", nullable = false) private UUID notificationId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(nullable = false) private String channel;
  @Column(nullable = false) private String status;
  @Column(nullable = false) private int attempts;
  @Column(name = "next_attempt_at") private Instant nextAttemptAt;
  @Column(name = "deferred_reason") private String deferredReason;
  @Column(name = "last_error") private String lastError;
  @Column(name = "sent_at") private Instant sentAt;

  protected Delivery() {}

  private Delivery(UUID notificationId, UUID userId, String channel, String status) {
    this.notificationId = notificationId;
    this.userId = userId;
    this.channel = channel;
    this.status = status;
  }

  public static Delivery pending(UUID notificationId, UUID userId, String channel, Instant dueAt, String deferredReason) {
    Delivery d = new Delivery(notificationId, userId, channel, "PENDING");
    d.nextAttemptAt = dueAt;
    d.deferredReason = deferredReason;
    return d;
  }

  public static Delivery skipped(UUID notificationId, UUID userId, String channel, String reason) {
    Delivery d = new Delivery(notificationId, userId, channel, "SKIPPED");
    d.lastError = reason;
    return d;
  }

  public static Delivery sentNow(UUID notificationId, UUID userId, String channel, Instant now) {
    Delivery d = new Delivery(notificationId, userId, channel, "SENT");
    d.attempts = 1;
    d.sentAt = now;
    return d;
  }

  public boolean isDue(Instant now) {
    return "PENDING".equals(status) && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
  }

  public int beginAttempt() {
    return ++attempts;
  }

  public void sent(Instant now) {
    status = "SENT";
    sentAt = now;
    nextAttemptAt = null;
    lastError = null;
  }

  public void retryAt(Instant next, String error) {
    nextAttemptAt = next;
    lastError = error;
    deferredReason = null;
  }

  public void failed(String error) {
    status = "FAILED";
    nextAttemptAt = null;
    lastError = error;
  }

  public void skip(String reason) {
    status = "SKIPPED";
    nextAttemptAt = null;
    lastError = reason;
  }

  public UUID getNotificationId() { return notificationId; }
  public UUID getUserId() { return userId; }
  public String getChannel() { return channel; }
  public String getStatus() { return status; }
  public int getAttempts() { return attempts; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public String getDeferredReason() { return deferredReason; }
  public String getLastError() { return lastError; }
  public Instant getSentAt() { return sentAt; }
}
