package in.societyos.community.notification.domain;

import in.societyos.community.common.CommunityDomainEvent;
import in.societyos.community.platform.core.UuidV7;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code community.notification.requested}: asks notification-service to reach residents.
 * {@code recipientRoles} is the optional catalogue addition for role-targeted notices. Params hold
 * display values only (titles, dates, facility names), never contact data.
 */
public record NotificationRequested(
    List<UUID> recipientUserIds,
    List<String> recipientRoles,
    String category,
    String template,
    Map<String, String> params,
    List<String> channels,
    String priority,
    String dedupeKey)
    implements CommunityDomainEvent {

  public static final String NOTICE = "NOTICE";
  public static final String BOOKING = "BOOKING";

  public NotificationRequested {
    recipientUserIds = List.copyOf(recipientUserIds);
    recipientRoles = recipientRoles == null ? List.of() : List.copyOf(recipientRoles);
    params = Map.copyOf(params);
    channels = List.copyOf(channels);
  }

  @Override
  public String type() {
    return "community.notification.requested";
  }

  @Override
  public UUID aggregateId() {
    return recipientUserIds.isEmpty() ? UuidV7.next() : recipientUserIds.getFirst();
  }

  @Override
  public String subject() {
    return "notification/" + dedupeKey;
  }
}
