package in.societyos.ticket.notification.application;

import in.societyos.ticket.notification.domain.NotificationRequested;
import in.societyos.ticket.platform.core.UuidV7;
import in.societyos.ticket.platform.events.DomainEvents;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Asks notification-service to send a message, through {@code ticket.notification.requested}. */
@Service
public class TicketNotifications {

  public static final String COMPLAINT = "COMPLAINT";
  public static final String JOBCARD = "JOBCARD";
  public static final String ALERT = "ALERT";
  public static final String APPROVAL = "APPROVAL";

  private final DomainEvents events;

  public TicketNotifications(DomainEvents events) {
    this.events = events;
  }

  /** Normal-priority push + in-app message. Recipients that are null are dropped; none means no event. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void send(Collection<UUID> recipients, String category, String template, Map<String, String> params,
      String dedupeKey) {
    publish(recipients, category, template, params, List.of("PUSH", "INAPP"), "NORMAL", dedupeKey);
  }

  /** High priority (SLA breach, escalation, P1): push, in-app and SMS fallback. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void urgent(Collection<UUID> recipients, String category, String template, Map<String, String> params,
      String dedupeKey) {
    publish(recipients, category, template, params, List.of("PUSH", "INAPP", "SMS"), "HIGH", dedupeKey);
  }

  private void publish(Collection<UUID> recipients, String category, String template, Map<String, String> params,
      List<String> channels, String priority, String dedupeKey) {
    List<UUID> to = recipients.stream().filter(Objects::nonNull).distinct().toList();
    if (to.isEmpty()) {
      return;
    }
    Map<String, String> safe = new LinkedHashMap<>();
    params.forEach((k, v) -> {
      if (v != null) {
        safe.put(k, v);
      }
    });
    events.publish(new NotificationRequested(UuidV7.next(), to, category, template, safe, channels, priority,
        dedupeKey));
  }
}
