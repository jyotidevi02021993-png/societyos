package in.societyos.utility.common;

import in.societyos.utility.platform.core.UuidV7;
import in.societyos.utility.platform.events.DomainEvents;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Publishes {@code utility.notification.requested} to the facility and estate managers. */
@Component
public class Alerts {

  static final List<String> MANAGER_ROLES = List.of("FACILITY_MANAGER", "ESTATE_MANAGER");

  private final DomainEvents events;

  public Alerts(DomainEvents events) {
    this.events = events;
  }

  /** Must run inside the caller's transaction. */
  public void notifyManagers(String template, Map<String, ?> params, String priority, String dedupeKey) {
    Map<String, String> p = new LinkedHashMap<>();
    params.forEach((k, v) -> p.put(k, Objects.toString(v, "")));
    List<String> channels = "HIGH".equals(priority) ? List.of("PUSH", "INAPP", "WHATSAPP") : List.of("PUSH", "INAPP");
    events.publish(new NotificationRequested(UuidV7.next(), List.of(), MANAGER_ROLES, "ALERT", template, p, channels,
        priority, dedupeKey));
  }
}
