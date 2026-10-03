package in.societyos.ticket.escalation.infrastructure;

import in.societyos.ticket.escalation.application.SlaEscalationService;
import in.societyos.ticket.jobcard.application.JobCardService;
import in.societyos.ticket.platform.events.CloudEvent;
import in.societyos.ticket.platform.events.DomainEventListener;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * workflow-service events. Groups (each with DLQ {@code sos.dlq.<group>}):
 * {@code ticket.sla-escalation} (SLA warning / breach / escalation) and
 * {@code ticket.jobcard-approvals} (cost approval of completed job cards).
 */
@Component
class WorkflowEventsListener {

  static final String TOPIC = "sos.workflow.events.v1";

  record SlaTimerData(UUID timerId, String subjectType, UUID subjectId, String kind, Instant dueAt) {}

  record EscalatedData(String subjectType, UUID subjectId, int level, String toRole) {}

  record ApprovalRequestedData(UUID taskId, UUID instanceId, String subjectType, UUID subjectId, String step,
      String approverRole, UUID approverUserId, Long amountPaise) {}

  record InstanceDecidedData(UUID instanceId, String subjectType, UUID subjectId, UUID decidedBy, String comment) {}

  private final SlaEscalationService sla;
  private final JobCardService jobCards;

  WorkflowEventsListener(SlaEscalationService sla, JobCardService jobCards) {
    this.sla = sla;
    this.jobCards = jobCards;
  }

  @DomainEventListener(topic = TOPIC, group = "ticket.sla-escalation", type = "workflow.sla.warning")
  void onWarning(CloudEvent<SlaTimerData> e) {
    SlaTimerData d = e.data();
    sla.warning(d.subjectType(), d.subjectId(), d.kind(), d.dueAt());
  }

  @DomainEventListener(topic = TOPIC, group = "ticket.sla-escalation", type = "workflow.sla.breached")
  void onBreached(CloudEvent<SlaTimerData> e) {
    SlaTimerData d = e.data();
    sla.breached(d.subjectType(), d.subjectId(), d.kind(), d.dueAt());
  }

  @DomainEventListener(topic = TOPIC, group = "ticket.sla-escalation", type = "workflow.escalated")
  void onEscalated(CloudEvent<EscalatedData> e) {
    EscalatedData d = e.data();
    sla.escalated(d.subjectType(), d.subjectId(), d.level(), d.toRole());
  }

  @DomainEventListener(topic = TOPIC, group = "ticket.jobcard-approvals", type = "workflow.approval.requested")
  void onApprovalRequested(CloudEvent<ApprovalRequestedData> e) {
    ApprovalRequestedData d = e.data();
    if ("JOBCARD".equals(d.subjectType())) {
      jobCards.approvalRequested(d.subjectId(), d.instanceId());
    }
  }

  @DomainEventListener(topic = TOPIC, group = "ticket.jobcard-approvals", type = "workflow.instance.approved")
  void onApproved(CloudEvent<InstanceDecidedData> e) {
    InstanceDecidedData d = e.data();
    if ("JOBCARD".equals(d.subjectType())) {
      jobCards.approvalDecided(d.subjectId(), d.instanceId(), true, d.comment());
    }
  }

  @DomainEventListener(topic = TOPIC, group = "ticket.jobcard-approvals", type = "workflow.instance.rejected")
  void onRejected(CloudEvent<InstanceDecidedData> e) {
    InstanceDecidedData d = e.data();
    if ("JOBCARD".equals(d.subjectType())) {
      jobCards.approvalDecided(d.subjectId(), d.instanceId(), false, d.comment());
    }
  }
}
