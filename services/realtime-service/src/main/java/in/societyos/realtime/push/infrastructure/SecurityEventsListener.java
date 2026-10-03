package in.societyos.realtime.push.infrastructure;

import in.societyos.realtime.platform.events.CloudEvent;
import in.societyos.realtime.platform.events.DomainEventListener;
import in.societyos.realtime.push.application.PushService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Group {@code realtime.security-push} on {@code sos.security.events.v1} (DLQ
 * {@code sos.dlq.realtime.security-push}). One pod per event consumes it; Redis fans it out.
 */
@Component
class SecurityEventsListener {

  private final PushService push;

  SecurityEventsListener(PushService push) {
    this.push = push;
  }

  @DomainEventListener(topic = "sos.security.events.v1", group = "realtime.security-push", type = {
      "security.entry.requested", "security.entry.approved", "security.entry.denied", "security.entry.expired",
      "security.entry.checked_in", "security.entry.checked_out", "security.sos.raised",
      "security.incident.reported"})
  void on(CloudEvent<JsonNode> event) {
    push.push(event);
  }
}
