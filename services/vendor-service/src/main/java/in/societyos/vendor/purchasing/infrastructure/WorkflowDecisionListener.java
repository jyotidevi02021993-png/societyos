package in.societyos.vendor.purchasing.infrastructure;

import in.societyos.vendor.platform.events.CloudEvent;
import in.societyos.vendor.platform.events.DomainEventListener;
import in.societyos.vendor.purchasing.application.PurchaseOrderService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PO approvals run in workflow-service (started by {@code vendor.po.submitted}); this listener
 * applies the outcome. Group {@code vendor.po-approvals}, DLQ {@code sos.dlq.vendor.po-approvals}.
 * Only subjects of type {@code PO} are handled; the rest of the topic is ignored.
 */
@Component
class WorkflowDecisionListener {

  static final String TOPIC = "sos.workflow.events.v1";
  static final String GROUP = "vendor.po-approvals";
  static final String PO = "PO";

  record ApprovalRequested(UUID taskId, UUID instanceId, String subjectType, UUID subjectId, String step,
      String approverRole, UUID approverUserId, Long amountPaise) {}

  record InstanceDecided(UUID instanceId, String subjectType, UUID subjectId, UUID decidedBy, String comment) {}

  private final PurchaseOrderService orders;

  WorkflowDecisionListener(PurchaseOrderService orders) {
    this.orders = orders;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "workflow.approval.requested")
  void onApprovalRequested(CloudEvent<ApprovalRequested> e) {
    ApprovalRequested d = e.data();
    if (PO.equals(d.subjectType()) && d.subjectId() != null) {
      orders.approvalStarted(d.subjectId(), d.instanceId());
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "workflow.instance.approved")
  void onApproved(CloudEvent<InstanceDecided> e) {
    InstanceDecided d = e.data();
    if (PO.equals(d.subjectType()) && d.subjectId() != null) {
      orders.decided(d.subjectId(), d.instanceId(), true, d.decidedBy(), d.comment());
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "workflow.instance.rejected")
  void onRejected(CloudEvent<InstanceDecided> e) {
    InstanceDecided d = e.data();
    if (PO.equals(d.subjectType()) && d.subjectId() != null) {
      orders.decided(d.subjectId(), d.instanceId(), false, d.decidedBy(), d.comment());
    }
  }
}
