package in.societyos.inventory.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.inventory.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.inventory.events.v1}. */
public interface InventoryEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "inventory";
  }
}
