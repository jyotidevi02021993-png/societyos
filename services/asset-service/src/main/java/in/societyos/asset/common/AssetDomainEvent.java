package in.societyos.asset.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.asset.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.asset.events.v1}. */
public interface AssetDomainEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "asset";
  }
}
