package in.societyos.workflow.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.workflow.platform.events.DomainEvent;

/** A domain event of this bounded context: published on {@code sos.workflow.events.v1}. */
public interface WorkflowEvent extends DomainEvent {

  @Override
  @JsonIgnore
  default String context() {
    return "workflow";
  }
}
