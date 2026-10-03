package in.societyos.billing.bill.domain;

import in.societyos.billing.common.Paise;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Pure rules for due dates and late fees. */
public final class DuesRules {

  /** Residents always get at least this many days between publication and the due date. */
  public static final int MIN_NOTICE_DAYS = 7;

  private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

  private DuesRules() {}

  public static String period(YearMonth month) {
    return month.format(PERIOD);
  }

  /**
   * The society's {@code billingDueDay} (1-28) of the billed month, but never less than
   * {@link #MIN_NOTICE_DAYS} after {@code billDate} (a late publication moves the due date out).
   */
  public static LocalDate dueDate(YearMonth month, int billingDueDay, LocalDate billDate) {
    LocalDate byDay = month.atDay(Math.clamp(billingDueDay, 1, month.lengthOfMonth()));
    LocalDate earliest = billDate.plusDays(MIN_NOTICE_DAYS);
    return byDay.isBefore(earliest) ? earliest : byDay;
  }

  /** Overdue from the day after the due date. */
  public static boolean isOverdue(LocalDate dueDate, LocalDate today) {
    return today.isAfter(dueDate);
  }

  public static long daysOverdue(LocalDate dueDate, LocalDate today) {
    return Math.max(0, ChronoUnit.DAYS.between(dueDate, today));
  }

  /** The late fee falls due once the grace period after the due date has passed. */
  public static boolean lateFeeDue(LocalDate dueDate, int graceDays, LocalDate today) {
    return today.isAfter(dueDate.plusDays(graceDays));
  }

  /**
   * One-time late fee on a bill's overdue balance: {@code FIXED} = value paise (a flat fee),
   * {@code PERCENT} = value basis points of the balance rounded half-up to the paisa,
   * {@code NONE} = 0. Nothing is charged on a zero balance.
   */
  public static long lateFee(String kind, long value, long overdueBalancePaise) {
    if (overdueBalancePaise <= 0 || value <= 0 || kind == null) {
      return 0;
    }
    return switch (kind) {
      case "FIXED" -> value;
      case "PERCENT" -> Paise.percent(overdueBalancePaise, value);
      default -> 0;
    };
  }
}
