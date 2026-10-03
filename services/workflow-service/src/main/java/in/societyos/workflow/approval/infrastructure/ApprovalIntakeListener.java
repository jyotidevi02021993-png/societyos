package in.societyos.workflow.approval.infrastructure;

import in.societyos.workflow.approval.application.ApprovalService;
import in.societyos.workflow.platform.events.CloudEvent;
import in.societyos.workflow.platform.events.DomainEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Starts approvals from the owners' events (catalogue, workflow section). Groups, each with DLQ
 * {@code sos.dlq.<group>}: {@code workflow.jobcard-approvals}, {@code workflow.po-approvals},
 * {@code workflow.expense-approvals}.
 */
@Component
class ApprovalIntakeListener {

  record JobCardCompleted(UUID jobCardId, String number, Long labourCostPaise, Long spareCostPaise,
      Long totalCostPaise) {}

  record PoSubmitted(UUID poId, String number, UUID vendorId, Long amountPaise) {}

  record ExpenseSubmitted(UUID expenseId, String number, String category, Long amountPaise) {}

  private final ApprovalService approvals;

  ApprovalIntakeListener(ApprovalService approvals) {
    this.approvals = approvals;
  }

  @DomainEventListener(topic = "sos.ticket.events.v1", group = "workflow.jobcard-approvals",
      type = "ticket.jobcard.completed")
  void onJobCardCompleted(CloudEvent<JobCardCompleted> e) {
    JobCardCompleted j = e.data();
    approvals.start("JOBCARD", j.jobCardId(), j.number(), paise(j.totalCostPaise()));
  }

  @DomainEventListener(topic = "sos.vendor.events.v1", group = "workflow.po-approvals", type = "vendor.po.submitted")
  void onPoSubmitted(CloudEvent<PoSubmitted> e) {
    PoSubmitted p = e.data();
    approvals.start("PO", p.poId(), p.number(), paise(p.amountPaise()));
  }

  @DomainEventListener(topic = "sos.billing.events.v1", group = "workflow.expense-approvals",
      type = "billing.expense.submitted")
  void onExpenseSubmitted(CloudEvent<ExpenseSubmitted> e) {
    ExpenseSubmitted x = e.data();
    approvals.start("EXPENSE", x.expenseId(), x.number(), paise(x.amountPaise()));
  }

  private static long paise(Long v) {
    return v == null ? 0 : v;
  }
}
