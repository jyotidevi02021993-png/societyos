package in.societyos.workflow.approval.domain;

import in.societyos.workflow.common.WorkflowEvent;
import java.util.UUID;

/** Approval events, exactly as in contracts/events/CATALOGUE.md (workflow section). */
public final class ApprovalEvents {

  private ApprovalEvents() {}

  public record ApprovalRequested(UUID taskId, UUID instanceId, String subjectType, UUID subjectId, String step,
      String approverRole, UUID approverUserId, long amountPaise) implements WorkflowEvent {
    @Override public String type() { return "workflow.approval.requested"; }
    @Override public UUID aggregateId() { return instanceId; }
  }

  public record InstanceApproved(UUID instanceId, String subjectType, UUID subjectId, UUID decidedBy,
      String comment) implements WorkflowEvent {
    @Override public String type() { return "workflow.instance.approved"; }
    @Override public UUID aggregateId() { return instanceId; }
  }

  public record InstanceRejected(UUID instanceId, String subjectType, UUID subjectId, UUID decidedBy,
      String comment) implements WorkflowEvent {
    @Override public String type() { return "workflow.instance.rejected"; }
    @Override public UUID aggregateId() { return instanceId; }
  }

  public static ApprovalRequested requested(WorkflowInstance i, ApprovalTask t) {
    return new ApprovalRequested(t.getId(), i.getId(), i.getSubjectType(), i.getSubjectId(),
        Integer.toString(t.getStep()), t.getApproverRole(), t.getApproverUserId(), i.getAmountPaise());
  }

  public static InstanceApproved approved(WorkflowInstance i) {
    return new InstanceApproved(i.getId(), i.getSubjectType(), i.getSubjectId(), i.getDecidedBy(), i.getComment());
  }

  public static InstanceRejected rejected(WorkflowInstance i) {
    return new InstanceRejected(i.getId(), i.getSubjectType(), i.getSubjectId(), i.getDecidedBy(), i.getComment());
  }
}
