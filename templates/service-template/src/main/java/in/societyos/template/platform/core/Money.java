package in.societyos.template.platform.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/** An amount of money in the smallest currency unit (paise for INR). Never a float. */
public record Money(long amountPaise, String currency) implements Comparable<Money> {

  public static final String INR = "INR";
  public static final Money ZERO = new Money(0, INR);

  public Money {
    Objects.requireNonNull(currency, "currency");
    if (currency.length() != 3) {
      throw new IllegalArgumentException("currency must be an ISO 4217 code: " + currency);
    }
  }

  public static Money ofPaise(long paise) {
    return new Money(paise, INR);
  }

  /** Converts rupees (e.g. "4250.50") to paise, rejecting more than two decimals. */
  public static Money ofRupees(BigDecimal rupees) {
    return ofPaise(rupees.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact());
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(amountPaise, other.amountPaise), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.subtractExact(amountPaise, other.amountPaise), currency);
  }

  public Money times(long quantity) {
    return new Money(Math.multiplyExact(amountPaise, quantity), currency);
  }

  /** Multiplies by a rate (e.g. GST 18% = 0.18) rounding half-up to the nearest paisa. */
  public Money times(BigDecimal rate) {
    long result =
        BigDecimal.valueOf(amountPaise).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
    return new Money(result, currency);
  }

  public Money negate() {
    return new Money(Math.negateExact(amountPaise), currency);
  }

  public boolean isNegative() {
    return amountPaise < 0;
  }

  public boolean isZero() {
    return amountPaise == 0;
  }

  public BigDecimal toRupees() {
    return BigDecimal.valueOf(amountPaise, 2);
  }

  /** Indian formatting, e.g. ₹4,25,000.00. */
  public String format() {
    NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
    return nf.format(toRupees());
  }

  @Override
  public int compareTo(Money o) {
    requireSameCurrency(o);
    return Long.compare(amountPaise, o.amountPaise);
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
    }
  }
}
