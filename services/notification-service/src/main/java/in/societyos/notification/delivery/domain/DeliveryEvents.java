package in.societyos.notification.delivery.domain;

import in.societyos.notification.common.NotificationDomainEvent;
import java.time.Instant;
import java.util.UUID;

/** {@code notification.message.delivered/failed} (catalogue: notificationId, userId, channel, category, reason). */
public final class DeliveryEvents {

  private DeliveryEvents() {}

  public record MessageDelivered(UUID notificationId, UUID userId, String channel, String category, String reason)
      implements NotificationDomainEvent {
    @Override public String type() { return "notification.message.delivered"; }
    @Override public UUID aggregateId() { return notificationId; }
  }

  public record MessageFailed(UUID notificationId, UUID userId, String channel, String category, String reason)
      implements NotificationDomainEvent {
    @Override public String type() { return "notification.message.failed"; }
    @Override public UUID aggregateId() { return notificationId; }
  }

  /** {@code notification.messages.purged}: retention purge for audit (proposed catalogue addition). */
  public record MessagesPurged(UUID societyId, Instant before, int retentionDays, int notifications)
      implements NotificationDomainEvent {
    @Override public String type() { return "notification.messages.purged"; }
    @Override public UUID aggregateId() { return societyId; }
  }
}
