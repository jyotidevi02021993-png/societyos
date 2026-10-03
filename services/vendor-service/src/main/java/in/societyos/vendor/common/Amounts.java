package in.societyos.vendor.common;

/** Money arithmetic on paise (long), rounding half up to the paisa. */
public final class Amounts {

  private Amounts() {}

  /** GST on a taxable amount at a whole-number rate, rounded half up. */
  public static long gstPaise(long taxablePaise, int gstPercent) {
    if (taxablePaise < 0 || gstPercent < 0) {
      throw new IllegalArgumentException("amount and rate must not be negative");
    }
    return Math.addExact(Math.multiplyExact(taxablePaise, gstPercent), 50) / 100;
  }

  public static long linePaise(int qty, long unitPricePaise) {
    return Math.multiplyExact(qty, unitPricePaise);
  }
}
