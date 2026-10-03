package in.societyos.security.notification.domain;

import in.societyos.security.platform.core.UuidV7;
import in.societyos.security.platform.events.DomainEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code security.notification.requested}: asks notification-service to reach people. Params hold
 * display values only (visitor name, flat label), never phone numbers.
 */
public record NotificationRequested(
    List<UUID> recipientUserIds,
    String category,
    String template,
    Map<String, String> params,
    List<String> channels,
    String priority,
    String dedupeKey)
    implements DomainEvent {

  public static final String GATE = "GATE";
  public static final String ALERT = "ALERT";

  public NotificationRequested {
    recipientUserIds = List.copyOf(recipientUserIds);
    params = Map.copyOf(params);
    channels = List.copyOf(channels);
  }

  /** Walk-in approvals: push first, SMS as fallback (the fallback timing is notification-service's). */
  public static NotificationRequested urgent(
      List<UUID> recipients, String category, String template, Map<String, String> params, String dedupeKey) {
    return new NotificationRequested(recipients, category, template, params, List.of("PUSH", "SMS"), "HIGH", dedupeKey);
  }

  public static NotificationRequested info(
      List<UUID> recipients, String category, String template, Map<String, String> params, String dedupeKey) {
    return new NotificationRequested(recipients, category, template, params, List.of("PUSH", "INAPP"), "NORMAL", dedupeKey);
  }

  @Override
  public String type() {
    return "security.notification.requested";
  }

  @Override
  public String context() {
    return "security";
  }

  /** Keyed by the first recipient, so one user's notifications stay in order. */
  @Override
  public UUID aggregateId() {
    return recipientUserIds.isEmpty() ? UuidV7.next() : recipientUserIds.getFirst();
  }

  @Override
  public String subject() {
    return "notification/" + dedupeKey;
  }
}
