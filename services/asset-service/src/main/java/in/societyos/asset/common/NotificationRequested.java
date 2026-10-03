package in.societyos.asset.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code asset.notification.requested}: asks notification-service to send a message.
 * {@code recipientRoles} is an optional addition to the catalogue payload for societies that have
 * not configured alert recipients (notification-service resolves role members).
 */
public record NotificationRequested(
    @JsonIgnore UUID requestId,
    List<UUID> recipientUserIds,
    List<String> recipientRoles,
    String category,
    String template,
    Map<String, String> params,
    List<String> channels,
    String priority,
    String dedupeKey)
    implements AssetDomainEvent {

  @Override public String type() { return "asset.notification.requested"; }
  @Override public UUID aggregateId() { return requestId; }
  @Override public String subject() { return "notification/" + dedupeKey; }
}
