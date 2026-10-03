package in.societyos.community.notification.application;

import in.societyos.community.notification.domain.NotificationRequested;
import in.societyos.community.platform.events.DomainEvents;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes {@code community.notification.requested} in the caller's transaction (outbox). */
@Component
public class CommunityNotifier {

  private static final List<String> CHANNELS = List.of("PUSH", "INAPP");

  private final DomainEvents events;

  public CommunityNotifier(DomainEvents events) {
    this.events = events;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void notify(Collection<UUID> users, Collection<String> roles, String category, String template,
      Map<String, String> params, String dedupeKey) {
    List<UUID> to = users.stream().filter(Objects::nonNull).distinct().toList();
    List<String> r = roles == null ? List.of() : List.copyOf(roles);
    if (to.isEmpty() && r.isEmpty()) {
      return;
    }
    Map<String, String> safe = new HashMap<>();
    params.forEach((k, v) -> safe.put(k, v == null ? "" : v));
    events.publish(new NotificationRequested(to, r, category, template, safe, CHANNELS, "NORMAL", dedupeKey));
  }
}
