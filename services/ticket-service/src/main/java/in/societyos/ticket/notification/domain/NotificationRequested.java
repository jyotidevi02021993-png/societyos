package in.societyos.ticket.notification.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.ticket.common.TicketEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code ticket.notification.requested} (catalogue: {@code <context>.notification.requested}).
 * Params carry ticket numbers, labels and categories only, never phone numbers or photos.
 */
public record NotificationRequested(
    @JsonIgnore UUID requestId,
    List<UUID> recipientUserIds,
    String category,
    String template,
    Map<String, String> params,
    List<String> channels,
    String priority,
    String dedupeKey) implements TicketEvent {

  @Override public String type() { return "ticket.notification.requested"; }
  @Override public UUID aggregateId() { return requestId; }
  @Override public String subject() { return "notification/" + requestId; }
}
