package in.societyos.billing.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact money arithmetic on {@code long} paise. No floating point anywhere.
 *
 * <p><b>Rounding rule.</b> Only percentages can produce fractions of a paisa (GST, percentage late
 * fees). Each such amount is computed exactly and then rounded <b>once, half-up to the nearest
 * paisa</b>, per line: GST is computed per bill line on that line's (already exact) amount, and a
 * percentage late fee on the bill's overdue balance. Totals are plain sums of the rounded lines and
 * are never rounded again, so a bill's total always equals the sum of its printed lines.
 * Per-sq-ft and fixed charges are integer products (area x paise rate) and never round.
 * Overflow throws ({@link Math#multiplyExact}) instead of wrapping.
 */
public final class Paise {

  private Paise() {}

  /** {@code amount x bps / 10000}, rounded half-up to the paisa (e.g. 18% GST = 1800 bps). */
  public static long percent(long amountPaise, long basisPoints) {
    if (amountPaise < 0 || basisPoints < 0) {
      throw new IllegalArgumentException("amount and rate must not be negative");
    }
    return BigDecimal.valueOf(amountPaise)
        .multiply(BigDecimal.valueOf(basisPoints))
        .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  public static long times(long ratePaise, long quantity) {
    return Math.multiplyExact(ratePaise, quantity);
  }

  public static long plus(long a, long b) {
    return Math.addExact(a, b);
  }

  /** "4250.50" style rupees for notification parameters. */
  public static String rupees(long paise) {
    return BigDecimal.valueOf(paise, 2).toPlainString();
  }
}
