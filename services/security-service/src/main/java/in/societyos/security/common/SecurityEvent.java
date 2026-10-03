package in.societyos.security.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.security.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.security.events.v1}. */
public interface SecurityEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "security";
  }
}
