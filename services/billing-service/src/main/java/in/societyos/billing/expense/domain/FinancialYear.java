package in.societyos.billing.expense.domain;

import java.time.LocalDate;

/** Indian financial year, April to March, written {@code 2026-27}. */
public final class FinancialYear {

  private FinancialYear() {}

  public static String of(LocalDate date) {
    int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    return "%d-%02d".formatted(start, (start + 1) % 100);
  }

  public static boolean isValid(String fy) {
    if (fy == null || !fy.matches("\\d{4}-\\d{2}")) {
      return false;
    }
    int start = Integer.parseInt(fy.substring(0, 4));
    return Integer.parseInt(fy.substring(5)) == (start + 1) % 100;
  }
}
