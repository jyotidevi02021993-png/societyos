package in.societyos.workflow.definition.domain;

import java.util.List;
import java.util.Objects;

/**
 * The configurable part of a workflow definition (stored as JSONB). Example "PO" definition from
 * docs/architecture/06 §5: FACILITY_MANAGER, then ESTATE_MANAGER above ₹50,000, then 2 of the
 * RWA committee above ₹2,00,000; a step escalates after 24 h without a decision.
 *
 * @param thresholdPaise subjects costing this much or less are approved automatically
 * @param steps approval steps in order
 */
public record ApprovalPlan(long thresholdPaise, List<Step> steps) {

  /**
   * @param appliesAbovePaise the step is part of the chain only when the amount is above this
   * @param requiredApprovals approvals needed from holders of the role ("2 of 5")
   * @param escalateAfterMins no decision after this long → the task moves to {@code escalateToRole}
   */
  public record Step(String name, String approverRole, java.util.UUID approverUserId, long appliesAbovePaise,
      int requiredApprovals, Integer escalateAfterMins, String escalateToRole) {

    public Step {
      if ((approverRole == null || approverRole.isBlank()) && approverUserId == null) {
        throw new IllegalArgumentException("Each step needs an approverRole or approverUserId");
      }
      if (requiredApprovals < 1) {
        requiredApprovals = 1;
      }
      if (escalateAfterMins != null && escalateAfterMins <= 0) {
        throw new IllegalArgumentException("escalateAfterMins must be positive");
      }
      if (escalateAfterMins != null && (escalateToRole == null || escalateToRole.isBlank())) {
        throw new IllegalArgumentException("escalateAfterMins needs escalateToRole");
      }
      if (name == null || name.isBlank()) {
        name = approverRole != null ? approverRole : "Approver";
      }
    }
  }

  public ApprovalPlan {
    steps = steps == null ? List.of() : List.copyOf(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("A workflow needs at least one step");
    }
    if (thresholdPaise < 0) {
      throw new IllegalArgumentException("thresholdPaise cannot be negative");
    }
    steps.forEach(Objects::requireNonNull);
  }

  public boolean needsApproval(long amountPaise) {
    return amountPaise > thresholdPaise;
  }

  /** The steps this amount has to pass, in order (at least the first step). */
  public List<Step> stepsFor(long amountPaise) {
    List<Step> applicable = steps.stream().filter(s -> amountPaise > s.appliesAbovePaise()).toList();
    return applicable.isEmpty() ? List.of(steps.getFirst()) : applicable;
  }
}
