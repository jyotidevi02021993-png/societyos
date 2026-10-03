package in.societyos.asset.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The PM scheduling rules, free of persistence so they can be unit tested:
 *
 * <ul>
 *   <li>A calendar occurrence is generated once {@code today >= dueOn - leadDays}.
 *   <li>After generating, the plan moves to the next occurrence after the generated one.
 *   <li>No backlog: if the scheduler missed several occurrences, only the latest one that is
 *       already generatable is created; older ones are skipped.
 *   <li>Usage plans fall due when the usage since the last PM reaches the interval.
 * </ul>
 */
public final class PmSchedule {

  private PmSchedule() {}

  /** First occurrence on or after {@code from}. */
  public static LocalDate firstOnOrAfter(PmFrequency f, LocalDate anchor, LocalDate from) {
    if (!anchor.isBefore(from)) {
      return anchor;
    }
    long n = estimate(f, anchor, from);
    LocalDate d = f.occurrence(anchor, n);
    while (d.isBefore(from)) {
      d = f.occurrence(anchor, ++n);
    }
    while (n > 0 && !f.occurrence(anchor, n - 1).isBefore(from)) {
      d = f.occurrence(anchor, --n);
    }
    return d;
  }

  /** First occurrence strictly after {@code after}. */
  public static LocalDate nextAfter(PmFrequency f, LocalDate anchor, LocalDate after) {
    return firstOnOrAfter(f, anchor, after.plusDays(1));
  }

  /** True when a task for the occurrence {@code dueOn} should exist by {@code today}. */
  public static boolean isGeneratable(LocalDate dueOn, int leadDays, LocalDate today) {
    return dueOn != null && !dueOn.minusDays(leadDays).isAfter(today);
  }

  /**
   * The occurrence to generate today, skipping any older missed ones: the latest occurrence
   * {@code d >= nextDueOn} with {@code d - leadDays <= today}. Null when nothing is due yet.
   */
  public static LocalDate occurrenceToGenerate(
      PmFrequency f, LocalDate anchor, LocalDate nextDueOn, int leadDays, LocalDate today) {
    if (!isGeneratable(nextDueOn, leadDays, today)) {
      return null;
    }
    LocalDate latest = nextDueOn;
    LocalDate candidate = nextAfter(f, anchor, latest);
    while (isGeneratable(candidate, leadDays, today)) {
      latest = candidate;
      candidate = nextAfter(f, anchor, latest);
    }
    return latest;
  }

  /** Usage plans: due once the usage since the last PM reaches the interval. */
  public static boolean usageDue(BigDecimal lastDone, BigDecimal current, BigDecimal interval) {
    if (current == null || interval == null || interval.signum() <= 0) {
      return false;
    }
    BigDecimal base = lastDone == null ? BigDecimal.ZERO : lastDone;
    return current.subtract(base).compareTo(interval) >= 0;
  }

  /** A task still open after its due date is overdue. */
  public static boolean isOverdue(LocalDate dueOn, LocalDate today) {
    return dueOn.isBefore(today);
  }

  private static long estimate(PmFrequency f, LocalDate anchor, LocalDate from) {
    long days = ChronoUnit.DAYS.between(anchor, from);
    long step = ChronoUnit.DAYS.between(anchor, f.occurrence(anchor, 1));
    return Math.max(0, days / Math.max(1, step) - 1);
  }
}
