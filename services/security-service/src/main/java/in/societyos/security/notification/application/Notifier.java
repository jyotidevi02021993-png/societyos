package in.societyos.security.notification.application;

import in.societyos.security.notification.domain.NotificationRequested;
import in.societyos.security.platform.events.DomainEvents;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes {@code security.notification.requested} in the caller's transaction; skips empty audiences. */
@Service
public class Notifier {

  private final DomainEvents events;

  public Notifier(DomainEvents events) {
    this.events = events;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void urgent(Collection<UUID> to, String category, String template, Map<String, String> params, String dedupeKey) {
    List<UUID> recipients = distinct(to);
    if (!recipients.isEmpty()) {
      events.publish(NotificationRequested.urgent(recipients, category, template, params, dedupeKey));
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void info(Collection<UUID> to, String category, String template, Map<String, String> params, String dedupeKey) {
    List<UUID> recipients = distinct(to);
    if (!recipients.isEmpty()) {
      events.publish(NotificationRequested.info(recipients, category, template, params, dedupeKey));
    }
  }

  private static List<UUID> distinct(Collection<UUID> to) {
    return List.copyOf(new LinkedHashSet<>(to.stream().filter(java.util.Objects::nonNull).toList()));
  }
}
