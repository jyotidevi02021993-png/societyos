package in.societyos.vendor.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.vendor.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.vendor.events.v1}. */
public interface VendorEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "vendor";
  }
}
