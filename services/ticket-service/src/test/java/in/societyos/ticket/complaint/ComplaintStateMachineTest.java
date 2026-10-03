package in.societyos.ticket.complaint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.ticket.common.Texts;
import in.societyos.ticket.complaint.domain.Complaint;
import in.societyos.ticket.complaint.domain.ComplaintEvents;
import in.societyos.ticket.complaint.domain.ComplaintStatus;
import in.societyos.ticket.platform.core.error.ProblemException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ComplaintStateMachineTest {

  static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
  static final Duration WINDOW = Duration.ofDays(7);
  final UUID resident = UUID.randomUUID();

  Complaint complaint() {
    return new Complaint("CMP-2026-000001", UUID.randomUUID(), null, null, resident, null, "Plumbing", "P3",
        "Water leaking from the ceiling, call me on 98765 43210", NOW.plusSeconds(3600));
  }

  static String code(Throwable e) {
    return ((ProblemException) e).code();
  }

  @Test
  void raisedWorkedResolvedAcceptedAndRated() {
    Complaint c = complaint();
    assertThat(c.getStatus()).isEqualTo(ComplaintStatus.OPEN);
    UUID card = UUID.randomUUID();
    c.jobCardRaised(card);
    assertThat(c.getStatus()).isEqualTo(ComplaintStatus.IN_PROGRESS);
    assertThat(c.getJobCardId()).isEqualTo(card);
    c.resolve(NOW);
    assertThat(c.getResolvedAt()).isEqualTo(NOW);
    c.feedback(true, 4, "Quick fix", NOW);
    assertThat(c.getStatus()).isEqualTo(ComplaintStatus.CLOSED);
    assertThat(c.getRating()).isEqualTo(4);
    assertThat(c.getFeedback()).isEqualTo("Quick fix");
  }

  @Test
  void rejectedResolutionReopens() {
    Complaint c = complaint();
    c.jobCardRaised(UUID.randomUUID());
    c.resolve(NOW);
    c.feedback(false, null, "Still leaking", NOW);
    assertThat(c.getStatus()).isEqualTo(ComplaintStatus.REOPENED);
    assertThat(c.getReopenedCount()).isEqualTo(1);
    assertThat(c.getResolvedAt()).isNull();
    assertThat(c.isOpenForWork()).isTrue();
  }

  @Test
  void feedbackRules() {
    Complaint c = complaint();
    assertThatThrownBy(() -> c.feedback(true, 5, null, NOW)).extracting(ComplaintStateMachineTest::code)
        .isEqualTo("NOT_RESOLVED");
    c.resolve(NOW);
    assertThatThrownBy(() -> c.feedback(true, null, null, NOW)).extracting(ComplaintStateMachineTest::code)
        .isEqualTo("RATING_REQUIRED");
    assertThatThrownBy(() -> c.feedback(true, 6, null, NOW)).extracting(ComplaintStateMachineTest::code)
        .isEqualTo("INVALID_RATING");
  }

  @Test
  void reopenOnlyWithinTheWindow() {
    Complaint c = complaint();
    c.resolve(NOW);
    c.feedback(true, 5, null, NOW);
    assertThatThrownBy(() -> c.reopen(NOW.plus(Duration.ofDays(8)), WINDOW))
        .extracting(ComplaintStateMachineTest::code).isEqualTo("REOPEN_WINDOW_OVER");
    c.reopen(NOW.plus(Duration.ofDays(6)), WINDOW);
    assertThat(c.getStatus()).isEqualTo(ComplaintStatus.REOPENED);
    assertThat(c.getClosedAt()).isNull();
  }

  @Test
  void cancelAndRejectAreFinal() {
    Complaint cancelled = complaint();
    cancelled.cancel();
    assertThat(cancelled.getStatus().isFinal()).isTrue();
    assertThatThrownBy(() -> cancelled.jobCardRaised(UUID.randomUUID()))
        .extracting(ComplaintStateMachineTest::code).isEqualTo("INVALID_TRANSITION");

    Complaint rejected = complaint();
    assertThatThrownBy(() -> rejected.reject(" ")).extracting(ComplaintStateMachineTest::code)
        .isEqualTo("REASON_REQUIRED");
    rejected.reject("Duplicate of CMP-2026-000002");
    assertThat(rejected.getStatus()).isEqualTo(ComplaintStatus.REJECTED);
    assertThat(rejected.getStatus().next()).isEmpty();
  }

  @Test
  void inProgressCannotBeCancelledOrClosedDirectly() {
    Complaint c = complaint();
    c.jobCardRaised(UUID.randomUUID());
    assertThatThrownBy(c::cancel).extracting(ComplaintStateMachineTest::code).isEqualTo("INVALID_TRANSITION");
    assertThatThrownBy(() -> c.close(NOW)).extracting(ComplaintStateMachineTest::code).isEqualTo("INVALID_TRANSITION");
  }

  @Test
  void needsTextAndAPlace() {
    assertThatThrownBy(() -> new Complaint("CMP-1", null, null, null, resident, null, null, "P3", "Leak", NOW))
        .extracting(ComplaintStateMachineTest::code).isEqualTo("WHERE_REQUIRED");
    assertThatThrownBy(() -> new Complaint("CMP-1", UUID.randomUUID(), null, null, resident, null, null, "P3", " ", NOW))
        .extracting(ComplaintStateMachineTest::code).isEqualTo("TEXT_REQUIRED");
  }

  @Test
  void createdEventCarriesNoPhoneNumber() {
    var event = ComplaintEvents.created(complaint());
    assertThat(event.text()).doesNotContain("98765").contains("[redacted phone]");
    assertThat(Texts.redactContact("mail asha@example.com")).isEqualTo("mail [redacted email]");
    assertThat(event.type()).isEqualTo("ticket.complaint.created");
    assertThat(event.context()).isEqualTo("ticket");
  }
}
