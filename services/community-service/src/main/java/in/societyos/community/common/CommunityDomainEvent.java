package in.societyos.community.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.community.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.community.events.v1}. */
public interface CommunityDomainEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "community";
  }
}
