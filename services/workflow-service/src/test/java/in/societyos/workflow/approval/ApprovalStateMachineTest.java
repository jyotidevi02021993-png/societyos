package in.societyos.workflow.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.workflow.approval.domain.ApprovalTask;
import in.societyos.workflow.approval.domain.WorkflowInstance;
import in.societyos.workflow.definition.domain.ApprovalPlan;
import in.societyos.workflow.platform.core.error.ProblemException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalStateMachineTest {

  static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
  static final UUID A = UUID.randomUUID();
  static final UUID B = UUID.randomUUID();

  /** docs/architecture/06 §5: FM, then EM above 50k, then 2 of the committee above 2L. */
  static ApprovalPlan poPlan() {
    return new ApprovalPlan(0, List.of(
        new ApprovalPlan.Step("Facility manager", "FACILITY_MANAGER", null, 0, 1, 1440, "ESTATE_MANAGER"),
        new ApprovalPlan.Step("Estate manager", "ESTATE_MANAGER", null, 5_000_000, 1, null, null),
        new ApprovalPlan.Step("Committee", "RWA_COMMITTEE", null, 20_000_000, 2, null, null)));
  }

  @Test
  void planPicksStepsByAmount() {
    ApprovalPlan plan = poPlan();
    assertThat(plan.stepsFor(4_000_000)).extracting(ApprovalPlan.Step::approverRole).containsExactly("FACILITY_MANAGER");
    assertThat(plan.stepsFor(24_000_000)).extracting(ApprovalPlan.Step::approverRole)
        .containsExactly("FACILITY_MANAGER", "ESTATE_MANAGER", "RWA_COMMITTEE");
  }

  @Test
  void thresholdSkipsApproval() {
    ApprovalPlan plan = new ApprovalPlan(500_000, poPlan().steps());
    assertThat(plan.needsApproval(500_000)).isFalse();
    assertThat(plan.needsApproval(500_001)).isTrue();
  }

  @Test
  void invalidPlansAreRejected() {
    assertThatThrownBy(() -> new ApprovalPlan(0, List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalPlan.Step("x", null, null, 0, 1, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalPlan.Step("x", "FM", null, 0, 1, 60, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyTheRoleOrUserMayDecide() {
    ApprovalTask byRole = new ApprovalTask(UUID.randomUUID(), 1, "FM", "FACILITY_MANAGER", null, 1, null, null);
    assertThat(byRole.canBeDecidedBy(A, Set.of("FACILITY_MANAGER"))).isTrue();
    assertThat(byRole.canBeDecidedBy(A, Set.of("RESIDENT_OWNER"))).isFalse();
    ApprovalTask byUser = new ApprovalTask(UUID.randomUUID(), 1, "Named", null, B, 1, null, null);
    assertThat(byUser.canBeDecidedBy(B, Set.of())).isTrue();
    assertThat(byUser.canBeDecidedBy(A, Set.of())).isFalse();
  }

  @Test
  void twoOfFiveNeedsTwoApprovals() {
    ApprovalTask task = new ApprovalTask(UUID.randomUUID(), 3, "Committee", "RWA_COMMITTEE", null, 2, null, null);
    assertThat(task.approve(A, "ok", T0)).isFalse();
    assertThat(task.isPending()).isTrue();
    assertThat(task.approve(B, "ok", T0)).isTrue();
    assertThat(task.getStatus()).isEqualTo(ApprovalTask.Status.APPROVED);
    assertThatThrownBy(() -> task.approve(A, "again", T0)).isInstanceOf(ProblemException.class);
  }

  @Test
  void rejectionEndsTheTask() {
    ApprovalTask task = new ApprovalTask(UUID.randomUUID(), 1, "FM", "FACILITY_MANAGER", null, 2, null, null);
    task.reject(A, "Too costly", T0);
    assertThat(task.getStatus()).isEqualTo(ApprovalTask.Status.REJECTED);
    assertThatThrownBy(() -> task.reject(B, "no", T0)).isInstanceOf(ProblemException.class);
  }

  @Test
  void undecidedTaskEscalatesOnceWhenDue() {
    Instant due = T0.plusSeconds(3600);
    ApprovalTask task = new ApprovalTask(UUID.randomUUID(), 1, "FM", "FACILITY_MANAGER", null, 1, due,
        "ESTATE_MANAGER");
    assertThat(task.escalateIfDue(T0)).isFalse();
    assertThat(task.escalateIfDue(due)).isTrue();
    assertThat(task.getApproverRole()).isEqualTo("ESTATE_MANAGER");
    assertThat(task.getEscalationLevel()).isEqualTo(1);
    assertThat(task.escalateIfDue(due.plusSeconds(3600))).isFalse();
  }

  @Test
  void instanceEndsOnce() {
    WorkflowInstance i = new WorkflowInstance(null, null, "PO", UUID.randomUUID(), "PO-1", 100, T0);
    i.atStep(1);
    i.approve(A, "fine", T0);
    assertThat(i.getStatus()).isEqualTo(WorkflowInstance.Status.APPROVED);
    assertThat(i.isRunning()).isFalse();
    assertThatThrownBy(() -> i.reject(A, "no", T0)).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> new WorkflowInstance(null, null, "PO", UUID.randomUUID(), null, -1, T0))
        .isInstanceOf(ProblemException.class);
  }
}
