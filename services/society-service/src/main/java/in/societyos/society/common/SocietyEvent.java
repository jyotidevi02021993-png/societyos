package in.societyos.society.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.society.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.society.events.v1}. */
public interface SocietyEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "society";
  }
}
