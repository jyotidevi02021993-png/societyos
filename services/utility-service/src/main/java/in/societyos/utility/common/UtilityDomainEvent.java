package in.societyos.utility.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.utility.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.utility.events.v1}. */
public interface UtilityDomainEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "utility";
  }
}
