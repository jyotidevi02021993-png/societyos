package in.societyos.utility.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code utility.notification.requested}. utility-service does not know user ids of managers, so
 * it addresses roles ({@code recipientRoles}, an optional addition to the catalogue payload).
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
    implements UtilityDomainEvent {

  @Override public String type() { return "utility.notification.requested"; }
  @Override public UUID aggregateId() { return requestId; }
  @Override public String subject() { return "notification/" + dedupeKey; }
}
