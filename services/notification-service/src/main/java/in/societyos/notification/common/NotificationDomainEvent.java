package in.societyos.notification.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.notification.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.notification.events.v1}. */
public interface NotificationDomainEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "notification";
  }
}
