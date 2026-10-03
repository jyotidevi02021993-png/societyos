package in.societyos.billing.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.billing.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.billing.events.v1}. */
public interface BillingEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "billing";
  }
}
