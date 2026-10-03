package in.societyos.billing.notification.domain;

import in.societyos.billing.common.BillingEvent;
import in.societyos.billing.platform.core.UuidV7;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code billing.notification.requested}: asks notification-service to reach a flat's members.
 * Params hold display values only (flat label, bill number, rupee amounts, dates), never contact data.
 */
public record NotificationRequested(
    List<UUID> recipientUserIds,
    String category,
    String template,
    Map<String, String> params,
    List<String> channels,
    String priority,
    String dedupeKey)
    implements BillingEvent {

  public static final String BILLING = "BILLING";

  public NotificationRequested {
    recipientUserIds = List.copyOf(recipientUserIds);
    params = Map.copyOf(params);
    channels = List.copyOf(channels);
  }

  @Override
  public String type() {
    return "billing.notification.requested";
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
