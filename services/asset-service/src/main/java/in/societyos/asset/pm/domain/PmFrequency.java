package in.societyos.asset.pm.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.Arrays;
import java.util.Locale;

/** How often a PM plan recurs. {@link #USAGE_BASED} plans are driven by meter readings instead. */
public enum PmFrequency {
  DAILY(Period.ofDays(1)),
  WEEKLY(Period.ofDays(7)),
  FORTNIGHTLY(Period.ofDays(14)),
  MONTHLY(Period.ofMonths(1)),
  QUARTERLY(Period.ofMonths(3)),
  HALF_YEARLY(Period.ofMonths(6)),
  YEARLY(Period.ofYears(1)),
  USAGE_BASED(null);

  private final Period period;

  PmFrequency(Period period) {
    this.period = period;
  }

  public boolean isCalendar() {
    return period != null;
  }

  /**
   * The {@code n}-th occurrence counted from the anchor (n = 0 is the anchor itself). Always
   * computed from the anchor, never from the previous occurrence, so month ends do not drift:
   * a plan anchored on 31 Jan falls on 28/29 Feb and then again on 31 Mar.
   */
  public LocalDate occurrence(LocalDate anchor, long n) {
    if (period == null) {
      throw new IllegalStateException("Usage-based plans have no calendar occurrences");
    }
    if (period.getDays() > 0) {
      return anchor.plusDays(period.getDays() * n);
    }
    return anchor.plusMonths(period.toTotalMonths() * n);
  }

  public static PmFrequency parse(String value) {
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "frequency must be one of " + Arrays.toString(values()), e);
    }
  }
}
