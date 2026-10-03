package in.societyos.asset.pm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.core.tenant.TenantContext;
import in.societyos.asset.pm.domain.Checklist;
import in.societyos.asset.pm.domain.PmFrequency;
import in.societyos.asset.pm.domain.PmPlan;
import in.societyos.asset.pm.domain.PmSchedule;
import in.societyos.asset.pm.domain.PmTask;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PmSchedulingRulesTest {

  static final LocalDate JAN_31 = LocalDate.of(2026, 1, 31);

  @BeforeEach
  void tenant() {
    TenantContext.set(in.societyos.asset.platform.core.tenant.Tenant.system(UuidV7.next()));
  }

  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  @ParameterizedTest
  @CsvSource({
      "DAILY,       2026-01-10, 3, 2026-01-13",
      "WEEKLY,      2026-01-10, 2, 2026-01-24",
      "FORTNIGHTLY, 2026-01-10, 1, 2026-01-24",
      "MONTHLY,     2026-01-10, 1, 2026-02-10",
      "QUARTERLY,   2026-01-10, 1, 2026-04-10",
      "HALF_YEARLY, 2026-01-10, 1, 2026-07-10",
      "YEARLY,      2026-01-10, 1, 2027-01-10"})
  void occurrencesFollowTheFrequency(PmFrequency f, LocalDate anchor, long n, LocalDate expected) {
    assertThat(f.occurrence(anchor, n)).isEqualTo(expected);
  }

  @Test
  void monthEndsDoNotDrift() {
    assertThat(PmFrequency.MONTHLY.occurrence(JAN_31, 1)).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(PmFrequency.MONTHLY.occurrence(JAN_31, 2)).isEqualTo(LocalDate.of(2026, 3, 31));
    assertThat(PmSchedule.nextAfter(PmFrequency.MONTHLY, JAN_31, LocalDate.of(2026, 2, 28)))
        .isEqualTo(LocalDate.of(2026, 3, 31));
  }

  @Test
  void firstOccurrenceOnOrAfter() {
    LocalDate anchor = LocalDate.of(2026, 1, 1);
    assertThat(PmSchedule.firstOnOrAfter(PmFrequency.WEEKLY, anchor, LocalDate.of(2026, 1, 1))).isEqualTo(anchor);
    assertThat(PmSchedule.firstOnOrAfter(PmFrequency.WEEKLY, anchor, LocalDate.of(2026, 1, 2)))
        .isEqualTo(LocalDate.of(2026, 1, 8));
    assertThat(PmSchedule.firstOnOrAfter(PmFrequency.QUARTERLY, anchor, LocalDate.of(2026, 9, 29)))
        .isEqualTo(LocalDate.of(2026, 10, 1));
    // anchor in the future: the anchor itself
    assertThat(PmSchedule.firstOnOrAfter(PmFrequency.MONTHLY, anchor, LocalDate.of(2025, 6, 1))).isEqualTo(anchor);
  }

  @Test
  void leadDaysOpenTheWindowEarly() {
    LocalDate due = LocalDate.of(2026, 10, 10);
    assertThat(PmSchedule.isGeneratable(due, 3, LocalDate.of(2026, 10, 6))).isFalse();
    assertThat(PmSchedule.isGeneratable(due, 3, LocalDate.of(2026, 10, 7))).isTrue();
    assertThat(PmSchedule.isGeneratable(due, 0, LocalDate.of(2026, 10, 10))).isTrue();
  }

  @Test
  void missedOccurrencesAreSkippedNotBacklogged() {
    LocalDate anchor = LocalDate.of(2026, 9, 1);
    // scheduler was down from 1 to 29 Sep: only the latest weekly occurrence (29 Sep) is generated
    LocalDate due = PmSchedule.occurrenceToGenerate(PmFrequency.WEEKLY, anchor, anchor, 0, LocalDate.of(2026, 9, 29));
    assertThat(due).isEqualTo(LocalDate.of(2026, 9, 29));
    // nothing due yet
    assertThat(PmSchedule.occurrenceToGenerate(PmFrequency.WEEKLY, anchor, LocalDate.of(2026, 10, 6), 0,
        LocalDate.of(2026, 10, 1))).isNull();
  }

  @Test
  void planGeneratesOncePerOccurrenceAndAdvances() {
    PmPlan plan = calendarPlan(PmFrequency.MONTHLY, LocalDate.of(2026, 10, 5), 2, LocalDate.of(2026, 9, 29));
    assertThat(plan.getNextDueOn()).isEqualTo(LocalDate.of(2026, 10, 5));

    assertThat(plan.takeOccurrence(LocalDate.of(2026, 10, 2))).isNull();
    assertThat(plan.takeOccurrence(LocalDate.of(2026, 10, 3))).isEqualTo(LocalDate.of(2026, 10, 5));
    assertThat(plan.getNextDueOn()).isEqualTo(LocalDate.of(2026, 11, 5));
    // running again the same day does nothing: the job is idempotent
    assertThat(plan.takeOccurrence(LocalDate.of(2026, 10, 3))).isNull();
  }

  @Test
  void pastAnchorStartsAtTheNextOccurrenceFromToday() {
    PmPlan plan = calendarPlan(PmFrequency.WEEKLY, LocalDate.of(2026, 9, 1), 0, LocalDate.of(2026, 9, 29));
    assertThat(plan.getNextDueOn()).isEqualTo(LocalDate.of(2026, 9, 29));
    assertThat(plan.takeOccurrence(LocalDate.of(2026, 9, 29))).isEqualTo(LocalDate.of(2026, 9, 29));
  }

  @Test
  void inactivePlansGenerateNothing() {
    PmPlan plan = calendarPlan(PmFrequency.DAILY, LocalDate.of(2026, 9, 29), 0, LocalDate.of(2026, 9, 29));
    plan.deactivate();
    assertThat(plan.takeOccurrence(LocalDate.of(2026, 9, 30))).isNull();
    plan.activate(LocalDate.of(2026, 10, 5));
    assertThat(plan.getNextDueOn()).isEqualTo(LocalDate.of(2026, 10, 5));
  }

  @Test
  void usagePlansFallDueAtTheInterval() {
    assertThat(PmSchedule.usageDue(null, new BigDecimal("249.9"), new BigDecimal("250"))).isFalse();
    assertThat(PmSchedule.usageDue(null, new BigDecimal("250"), new BigDecimal("250"))).isTrue();
    assertThat(PmSchedule.usageDue(new BigDecimal("250"), new BigDecimal("400"), new BigDecimal("250"))).isFalse();

    PmPlan dg = new PmPlan(UuidV7.next(), new PmPlan.Details("DG 250 h service", PmFrequency.USAGE_BASED, null, 0,
        "running_hours", new BigDecimal("250"), null, null, null, null), LocalDate.of(2026, 9, 29));
    assertThat(dg.getUsageMetric()).isEqualTo("RUNNING_HOURS");
    assertThat(dg.getNextDueOn()).isNull();
    assertThat(dg.takeOccurrence(LocalDate.of(2026, 9, 29))).isNull();
    assertThat(dg.recordUsage(new BigDecimal("120"))).isFalse();
    assertThat(dg.recordUsage(new BigDecimal("251"))).isTrue();
    dg.taskDone(new BigDecimal("251"));
    assertThat(dg.recordUsage(new BigDecimal("300"))).isFalse();
    // a lower (out-of-order) reading never moves usage backwards
    assertThat(dg.recordUsage(new BigDecimal("100"))).isFalse();
    assertThat(dg.getLatestUsage()).isEqualByComparingTo("300");
    assertThat(dg.recordUsage(new BigDecimal("501"))).isTrue();
  }

  @Test
  void planDetailsAreValidated() {
    assertThatThrownBy(() -> new PmPlan.Details("x", PmFrequency.MONTHLY, null, 0, null, null, null, null, null, null))
        .hasMessageContaining("anchorOn");
    assertThatThrownBy(() -> new PmPlan.Details("x", PmFrequency.USAGE_BASED, null, 0, "HOURS", null, null, null, null, null))
        .hasMessageContaining("usageInterval");
    assertThatThrownBy(() -> new PmPlan.Details("x", PmFrequency.DAILY, LocalDate.now(), 61, null, null, null, null, null, null))
        .hasMessageContaining("leadDays");
    assertThatThrownBy(() -> PmFrequency.parse("HOURLY")).hasMessageContaining("frequency must be one of");
  }

  @Test
  void taskLifecycle() {
    PmPlan plan = calendarPlan(PmFrequency.DAILY, LocalDate.of(2026, 9, 29), 0, LocalDate.of(2026, 9, 29));
    PmTask task = new PmTask("PM-2026-000001", plan, LocalDate.of(2026, 9, 29), PmTask.Trigger.CALENDAR);
    assertThat(task.getStatus()).isEqualTo(PmTask.Status.DUE);
    assertThat(task.markOverdue()).isTrue();
    assertThat(task.markOverdue()).as("overdue alert is sent once").isFalse();
    assertThat(task.getStatus()).isEqualTo(PmTask.Status.OVERDUE);
    UUID tech = UuidV7.next();
    task.start(tech, java.time.Instant.now());
    task.complete(tech, java.time.Instant.now(), "[]", 3, 0, null, null, 0, null);
    assertThat(task.getStatus()).isEqualTo(PmTask.Status.DONE);
    assertThatThrownBy(() -> task.start(tech, java.time.Instant.now())).isInstanceOf(IllegalStateException.class);
    task.miss();
    assertThat(task.getStatus()).as("a done task is never marked missed").isEqualTo(PmTask.Status.DONE);
  }

  @Test
  void checklistResultsAreChecked() {
    List<Checklist.Item> items = Checklist.validateItems(List.of(
        new Checklist.Item("oil", "Oil level", true),
        new Checklist.Item(null, "Battery terminals", true),
        new Checklist.Item("belt", "Fan belt", false)));
    assertThat(items).extracting(Checklist.Item::code).containsExactly("OIL", "ITEM_2", "BELT");

    Checklist.Evaluation eval = Checklist.evaluate(items, List.of(
        new Checklist.Result("oil", null, Checklist.Outcome.OK, null),
        new Checklist.Result("ITEM_2", null, Checklist.Outcome.NOT_OK, "Corroded"),
        new Checklist.Result("BELT", null, Checklist.Outcome.NA, null)));
    assertThat(eval.ok()).isEqualTo(1);
    assertThat(eval.failed()).isEqualTo(1);
    assertThat(eval.na()).isEqualTo(1);
    assertThat(eval.failures()).extracting(Checklist.Result::label).containsExactly("Battery terminals");

    assertThatThrownBy(() -> Checklist.evaluate(items, List.of(new Checklist.Result("OIL", null, Checklist.Outcome.OK, null))))
        .hasMessageContaining("Required item ITEM_2");
    assertThatThrownBy(() -> Checklist.evaluate(items, List.of(
        new Checklist.Result("OIL", null, Checklist.Outcome.NOT_OK, " "),
        new Checklist.Result("ITEM_2", null, Checklist.Outcome.OK, null))))
        .hasMessageContaining("add a note");
    assertThatThrownBy(() -> Checklist.evaluate(items, List.of(new Checklist.Result("XYZ", null, Checklist.Outcome.OK, null))))
        .hasMessageContaining("Unknown");
    assertThatThrownBy(() -> Checklist.validateItems(List.of(new Checklist.Item("a", "A", true), new Checklist.Item("A", "B", true))))
        .hasMessageContaining("Duplicate");
  }

  private static PmPlan calendarPlan(PmFrequency f, LocalDate anchor, int lead, LocalDate today) {
    return new PmPlan(UuidV7.next(), new PmPlan.Details("Plan", f, anchor, lead, null, null, null, null, null, null), today);
  }
}
