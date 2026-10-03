package in.societyos.workflow.sla.domain;

import in.societyos.workflow.common.WorkflowEvent;
import java.time.Instant;
import java.util.UUID;

/** SLA and escalation events, exactly as in contracts/events/CATALOGUE.md (workflow section). */
public final class SlaEvents {

  private SlaEvents() {}

  public record SlaWarning(UUID timerId, String subjectType, UUID subjectId, String kind, Instant dueAt)
      implements WorkflowEvent {
    @Override public String type() { return "workflow.sla.warning"; }
    @Override public UUID aggregateId() { return subjectId; }
  }

  public record SlaBreached(UUID timerId, String subjectType, UUID subjectId, String kind, Instant dueAt)
      implements WorkflowEvent {
    @Override public String type() { return "workflow.sla.breached"; }
    @Override public UUID aggregateId() { return subjectId; }
  }

  public record Escalated(String subjectType, UUID subjectId, int level, String toRole) implements WorkflowEvent {
    @Override public String type() { return "workflow.escalated"; }
    @Override public UUID aggregateId() { return subjectId; }
  }

  public static SlaWarning warning(SlaTimer t) {
    return new SlaWarning(t.getId(), t.getSubjectType(), t.getSubjectId(), t.getKind().name(), t.getDueAt());
  }

  public static SlaBreached breached(SlaTimer t) {
    return new SlaBreached(t.getId(), t.getSubjectType(), t.getSubjectId(), t.getKind().name(), t.getDueAt());
  }
}
