package in.societyos.ticket.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.ticket.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.ticket.events.v1}. */
public interface TicketEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "ticket";
  }
}
