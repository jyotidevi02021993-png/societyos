package in.societyos.workflow.sla;

import static org.assertj.core.api.Assertions.assertThat;

import in.societyos.workflow.sla.domain.EscalationStep;
import in.societyos.workflow.sla.domain.SlaTimer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlaTimerTest {

  static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
  static final List<EscalationStep> CHAIN =
      List.of(new EscalationStep(0, "FACILITY_MANAGER"), new EscalationStep(60, "ESTATE_MANAGER"));

  static SlaTimer timer() {
    return new SlaTimer("COMPLAINT", UUID.randomUUID(), "CMP-1", SlaTimer.Kind.RESOLVE, null, T0, 100, 80, "[]");
  }

  @Test
  void warnsThenBreachesThenEscalatesStepByStep() {
    SlaTimer t = timer();
    assertThat(t.nextWakeAt()).isEqualTo(T0.plus(Duration.ofMinutes(80)));
    assertThat(t.tick(T0.plus(Duration.ofMinutes(10)), CHAIN).isEmpty()).isTrue();

    SlaTimer.Tick warn = t.tick(T0.plus(Duration.ofMinutes(80)), CHAIN);
    assertThat(warn.warn()).isTrue();
    assertThat(warn.breach()).isFalse();
    assertThat(t.nextWakeAt()).isEqualTo(T0.plus(Duration.ofMinutes(100)));

    SlaTimer.Tick breach = t.tick(T0.plus(Duration.ofMinutes(100)), CHAIN);
    assertThat(breach.breach()).isTrue();
    assertThat(breach.escalations()).containsExactly(new SlaTimer.Escalation(1, "FACILITY_MANAGER"));
    assertThat(t.getStatus()).isEqualTo(SlaTimer.Status.FIRED);
    assertThat(t.nextWakeAt()).isEqualTo(T0.plus(Duration.ofMinutes(160)));

    SlaTimer.Tick second = t.tick(T0.plus(Duration.ofMinutes(160)), CHAIN);
    assertThat(second.breach()).isFalse();
    assertThat(second.escalations()).containsExactly(new SlaTimer.Escalation(2, "ESTATE_MANAGER"));
    assertThat(t.nextWakeAt()).isNull();
  }

  @Test
  void lateWakeUpCatchesUpOnEveryStep() {
    SlaTimer t = timer();
    SlaTimer.Tick tick = t.tick(T0.plus(Duration.ofHours(10)), CHAIN);
    assertThat(tick.breach()).isTrue();
    assertThat(tick.escalations()).hasSize(2);
    assertThat(t.getLevel()).isEqualTo(2);
  }

  @Test
  void stoppedTimerDoesNothing() {
    SlaTimer t = timer();
    assertThat(t.stop(T0.plusSeconds(60))).isTrue();
    assertThat(t.stop(T0.plusSeconds(120))).isFalse();
    assertThat(t.tick(T0.plus(Duration.ofHours(10)), CHAIN).isEmpty()).isTrue();
    assertThat(t.nextWakeAt()).isNull();
  }
}
