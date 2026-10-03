package in.societyos.ticket.jobcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.ticket.jobcard.domain.JobCard;
import in.societyos.ticket.jobcard.domain.JobCardStatus;
import in.societyos.ticket.platform.core.error.ProblemException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JobCardStateMachineTest {

  static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
  static final long THRESHOLD = 500_000;
  final UUID tech = UUID.randomUUID();
  final UUID supervisor = UUID.randomUUID();

  JobCard card() {
    return new JobCard("JC-2026-000001", "COMPLAINT", UUID.randomUUID(), null, null, null, "Plumbing", "P2",
        "Kitchen tap leaking");
  }

  JobCard inProgress() {
    JobCard c = card();
    c.assign(tech, null, NOW);
    c.start(NOW);
    return c;
  }

  @Test
  void happyPathToClosedAndLocked() {
    JobCard c = card();
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.OPEN);
    c.assign(tech, null, NOW);
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.ASSIGNED);
    c.start(NOW);
    c.waitFor("SPARE");
    assertThat(c.getWaitingReason()).isEqualTo("SPARE");
    c.resume();
    assertThat(c.getWaitingReason()).isNull();
    c.complete("Replaced washer", "Worn washer", 20_000, 5_000, null, true, THRESHOLD, NOW);
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.COMPLETED);
    assertThat(c.totalCostPaise()).isEqualTo(25_000);
    assertThat(c.getApprovalStatus()).isEqualTo(JobCard.Approval.NOT_REQUIRED);
    c.verify(supervisor, NOW);
    c.close(supervisor, true, NOW);
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.CLOSED);
    assertThat(c.isLocked()).isTrue();
    assertThat(c.getResidentConfirmed()).isTrue();

    assertThatThrownBy(() -> c.assign(tech, null, NOW)).isInstanceOf(ProblemException.class)
        .extracting(e -> ((ProblemException) e).code()).isEqualTo("JOB_CARD_LOCKED");
    assertThatThrownBy(c::reopen).extracting(e -> ((ProblemException) e).code()).isEqualTo("JOB_CARD_LOCKED");
    assertThatThrownBy(c::slaBreached).extracting(e -> ((ProblemException) e).code()).isEqualTo("JOB_CARD_LOCKED");
  }

  @Test
  void cannotSkipStates() {
    JobCard c = card();
    assertThatThrownBy(() -> c.start(NOW)).extracting(e -> ((ProblemException) e).code()).isEqualTo("INVALID_TRANSITION");
    assertThatThrownBy(() -> c.verify(supervisor, NOW)).extracting(e -> ((ProblemException) e).code())
        .isEqualTo("INVALID_TRANSITION");
    assertThatThrownBy(() -> c.close(supervisor, null, NOW)).extracting(e -> ((ProblemException) e).code())
        .isEqualTo("INVALID_TRANSITION");
    c.assign(tech, null, NOW);
    assertThatThrownBy(() -> c.complete("x", "y", 0, 0, null, true, THRESHOLD, NOW))
        .extracting(e -> ((ProblemException) e).code()).isEqualTo("INVALID_TRANSITION");
  }

  @Test
  void completionNeedsWorkRootCauseAndAfterPhoto() {
    JobCard c = inProgress();
    assertThatThrownBy(() -> c.complete(" ", "cause", 0, 0, null, true, THRESHOLD, NOW))
        .extracting(e -> ((ProblemException) e).code()).isEqualTo("WORK_DONE_REQUIRED");
    assertThatThrownBy(() -> c.complete("work", null, 0, 0, null, true, THRESHOLD, NOW))
        .extracting(e -> ((ProblemException) e).code()).isEqualTo("ROOT_CAUSE_REQUIRED");
    assertThatThrownBy(() -> c.complete("work", "cause", 0, 0, null, false, THRESHOLD, NOW))
        .extracting(e -> ((ProblemException) e).code()).isEqualTo("EVIDENCE_REQUIRED");
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.IN_PROGRESS);
  }

  @Test
  void expensiveWorkWaitsForApprovalAndRejectionSendsItBack() {
    JobCard c = inProgress();
    c.complete("Motor rewound", "Burnt winding", 400_000, 200_000, null, true, THRESHOLD, NOW);
    assertThat(c.getApprovalStatus()).isEqualTo(JobCard.Approval.PENDING);
    assertThatThrownBy(() -> c.verify(supervisor, NOW)).extracting(e -> ((ProblemException) e).code())
        .isEqualTo("APPROVAL_PENDING");

    UUID instance = UUID.randomUUID();
    c.approvalRequested(instance);
    assertThat(c.approvalDecided(UUID.randomUUID(), true)).as("other instance ignored").isFalse();
    assertThat(c.approvalDecided(instance, false)).isTrue();
    assertThat(c.getApprovalStatus()).isEqualTo(JobCard.Approval.REJECTED);
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.IN_PROGRESS);

    c.complete("Motor rewound", "Burnt winding", 400_000, 200_000, null, true, THRESHOLD, NOW);
    assertThat(c.getApprovalStatus()).isEqualTo(JobCard.Approval.PENDING);
    UUID second = UUID.randomUUID();
    c.approvalRequested(second);
    assertThat(c.approvalDecided(second, true)).isTrue();
    c.verify(supervisor, NOW);
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.VERIFIED);
  }

  @Test
  void residentRejectionReopensAndWorkRestarts() {
    JobCard c = inProgress();
    c.complete("Fixed", "Loose wire", 0, 0, null, true, THRESHOLD, NOW);
    c.verify(supervisor, NOW);
    c.reopen();
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.REOPENED);
    assertThat(c.getReopenedCount()).isEqualTo(1);
    assertThat(c.getResidentConfirmed()).isFalse();
    c.start(NOW);
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.IN_PROGRESS);
  }

  @Test
  void reworkFromCompletedAndReassignWhileWorking() {
    JobCard c = inProgress();
    UUID other = UUID.randomUUID();
    c.assign(other, null, NOW);
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.IN_PROGRESS);
    assertThat(c.isAssignedTo(other)).isTrue();
    c.complete("Fixed", "Loose wire", 0, 0, null, true, THRESHOLD, NOW);
    c.rework();
    assertThat(c.getStatus()).isEqualTo(JobCardStatus.IN_PROGRESS);
    assertThatThrownBy(() -> c.assign(null, null, NOW)).extracting(e -> ((ProblemException) e).code())
        .isEqualTo("ASSIGNEE_REQUIRED");
  }

  @Test
  void waitingNeedsAKnownReason() {
    JobCard c = inProgress();
    assertThatThrownBy(() -> c.waitFor("LUNCH")).extracting(e -> ((ProblemException) e).code())
        .isEqualTo("INVALID_WAITING_REASON");
  }

  @Test
  void escalationOnlyGoesUp() {
    JobCard c = inProgress();
    c.escalate(2, "ESTATE_MANAGER");
    c.escalate(1, "FACILITY_MANAGER");
    assertThat(c.getEscalationLevel()).isEqualTo(2);
    assertThat(c.getEscalatedToRole()).isEqualTo("ESTATE_MANAGER");
  }

  @ParameterizedTest
  @EnumSource(JobCardStatus.class)
  void closedIsTerminalAndEveryOtherStateHasAWayOut(JobCardStatus s) {
    if (s == JobCardStatus.CLOSED) {
      assertThat(s.next()).isEmpty();
    } else {
      assertThat(s.next()).isNotEmpty().doesNotContain(JobCardStatus.OPEN);
    }
  }

  @Test
  void onlyVerifiedCanClose() {
    for (JobCardStatus s : JobCardStatus.values()) {
      assertThat(s.canMoveTo(JobCardStatus.CLOSED)).as(s.name()).isEqualTo(s == JobCardStatus.VERIFIED);
    }
  }
}
