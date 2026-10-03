package in.societyos.workflow.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.workflow.platform.core.UuidV7;
import in.societyos.workflow.platform.events.DomainEvents;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes {@code workflow.notification.requested} (catalogue: {@code <context>.notification.requested}). */
@Service
public class WorkflowNotifications {

  public record NotificationRequested(@JsonIgnore UUID requestId, List<UUID> recipientUserIds, String category,
      String template, Map<String, String> params, List<String> channels, String priority, String dedupeKey)
      implements WorkflowEvent {
    @Override public String type() { return "workflow.notification.requested"; }
    @Override public UUID aggregateId() { return requestId; }
    @Override public String subject() { return "notification/" + requestId; }
  }

  private final DomainEvents events;

  public WorkflowNotifications(DomainEvents events) {
    this.events = events;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void send(Collection<UUID> recipients, String category, String template, Map<String, String> params,
      boolean urgent, String dedupeKey) {
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
    events.publish(new NotificationRequested(UuidV7.next(), to, category, template, safe,
        urgent ? List.of("PUSH", "INAPP", "SMS") : List.of("PUSH", "INAPP"), urgent ? "HIGH" : "NORMAL", dedupeKey));
  }
}
