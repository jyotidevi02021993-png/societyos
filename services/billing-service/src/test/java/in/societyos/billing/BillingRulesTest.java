package in.societyos.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.billing.bill.domain.Bill;
import in.societyos.billing.bill.domain.DuesRules;
import in.societyos.billing.common.Paise;
import in.societyos.billing.expense.domain.FinancialYear;
import in.societyos.billing.ledger.domain.Journal;
import in.societyos.billing.tariff.domain.TariffCalculator;
import in.societyos.billing.tariff.domain.TariffCalculator.Basis;
import in.societyos.billing.tariff.domain.TariffCalculator.FlatProfile;
import in.societyos.billing.tariff.domain.TariffCalculator.GstPolicy;
import in.societyos.billing.tariff.domain.TariffCalculator.OneOffCharge;
import in.societyos.billing.tariff.domain.TariffCalculator.Tariff;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Pure billing rules: tariffs, GST, rounding, due dates, late fees, bill balance, journals. */
class BillingRulesTest {

  static final Tariff MAINTENANCE = new Tariff("MAINT", "Maintenance", Basis.PER_SQFT, 350, null, true, true);
  static final Tariff SINKING = new Tariff("SINK", "Sinking fund", Basis.FIXED, 50_000, null, false, true);
  static final Tariff CLUB = new Tariff("CLUB", "Club", Basis.FLAT_TYPE, 20_000,
      Map.of("2BHK", 30_000L, "3BHK", 45_000L), true, false);
  static final GstPolicy GST = new GstPolicy(true, 1800, 750_000);

  @Nested
  class Tariffs {

    @Test
    void perSqftFixedAndFlatTypeAreExactProducts() {
      var r = TariffCalculator.calculate(new FlatProfile("A-101", 1000, "2BHK", "OCCUPIED"),
          List.of(MAINTENANCE, SINKING, CLUB), List.of(), GstPolicy.NONE);
      assertThat(r.lines()).extracting(TariffCalculator.Line::amountPaise).containsExactly(350_000L, 50_000L, 30_000L);
      assertThat(r.amountPaise()).isEqualTo(430_000);
      assertThat(r.gstPaise()).isZero();
      assertThat(r.lines().getFirst().description()).isEqualTo("Maintenance (1000 sq ft x Rs 3.50)");
    }

    @Test
    void flatTypeFallsBackToTheHeadRateAndVacantFlatsSkipExcludedHeads() {
      var other = TariffCalculator.calculate(new FlatProfile("A-1", 500, "1RK", "OCCUPIED"), List.of(CLUB), List.of(),
          GstPolicy.NONE);
      assertThat(other.amountPaise()).isEqualTo(20_000);
      var vacant = TariffCalculator.calculate(new FlatProfile("A-2", 1000, "2BHK", "VACANT"),
          List.of(MAINTENANCE, CLUB), List.of(), GstPolicy.NONE);
      assertThat(vacant.lines()).extracting(TariffCalculator.Line::code).containsExactly("MAINT");
    }

    @Test
    void missingAreaIsAWarningNotAnError() {
      var r = TariffCalculator.calculate(new FlatProfile("B-7", null, null, "OCCUPIED"), List.of(MAINTENANCE, SINKING),
          List.of(), GST);
      assertThat(r.amountPaise()).isEqualTo(50_000);
      assertThat(r.warnings()).singleElement().asString().contains("B-7");
    }

    @Test
    void gstIsExemptAtOrBelowTheRwaThresholdAndDueOnTheWholeAmountAbove() {
      // 2142 sq ft x 350 = 7,49,700 paise, + club 30,000 = 7,79,700 > 7,50,000 → GST on both taxable lines
      var above = TariffCalculator.calculate(new FlatProfile("A-1", 2142, "2BHK", "OCCUPIED"),
          List.of(MAINTENANCE, SINKING, CLUB), List.of(), GST);
      assertThat(above.lines()).extracting(TariffCalculator.Line::gstPaise).containsExactly(134_946L, 0L, 5_400L);
      assertThat(above.totalPaise()).isEqualTo(749_700 + 50_000 + 30_000 + 134_946 + 5_400);

      // exactly at the threshold: exempt
      var at = TariffCalculator.calculate(new FlatProfile("A-2", 2057, "2BHK", "OCCUPIED"),
          List.of(new Tariff("M", "M", Basis.PER_SQFT, 350, null, true, true),
              new Tariff("C", "C", Basis.FIXED, 30_050, null, true, true)), List.of(), GST);
      assertThat(at.amountPaise()).isEqualTo(750_000);
      assertThat(at.gstPaise()).isZero();

      // not registered: never GST
      var unregistered = TariffCalculator.calculate(new FlatProfile("A-1", 3000, "2BHK", "OCCUPIED"),
          List.of(MAINTENANCE), List.of(), new GstPolicy(false, 1800, 750_000));
      assertThat(unregistered.gstPaise()).isZero();
    }

    @Test
    void oneOffChargesAreAddedAndTaxedOnlyWhenFlaggedAndRegistered() {
      UUID ref = UUID.randomUUID();
      var r = TariffCalculator.calculate(new FlatProfile("A-1", 1000, "2BHK", "OCCUPIED"), List.of(SINKING),
          List.of(new OneOffCharge(ref, "Hall booking", 100_001, true), new OneOffCharge(null, "Key", 5_000, false)),
          GST);
      assertThat(r.lines()).hasSize(3);
      assertThat(r.lines().get(1).sourceRef()).isEqualTo(ref);
      assertThat(r.lines().get(1).gstPaise()).isEqualTo(18_000); // 18,000.18 → 18,000
      assertThat(r.lines().get(2).gstPaise()).isZero();
    }
  }

  @Nested
  class Rounding {

    @Test
    void percentRoundsHalfUpToThePaisaOnce() {
      assertThat(Paise.percent(1_000, 1800)).isEqualTo(180);
      assertThat(Paise.percent(3, 1800)).isEqualTo(1);      // 0.54 → 1
      assertThat(Paise.percent(25, 1800)).isEqualTo(5);     // 4.50 → 5 (half up)
      assertThat(Paise.percent(36, 1250)).isEqualTo(5);     // 4.50 → 5
      assertThat(Paise.percent(35, 1250)).isEqualTo(4);     // 4.375 → 4
      assertThat(Paise.percent(0, 1800)).isZero();
    }

    @Test
    void totalsAreSumsOfRoundedLinesNeverReRounded() {
      // three lines of 25 paise at 18%: 4.5 each → 5 each = 15, not round(13.5) = 14
      var three = List.of(new Tariff("A", "A", Basis.FIXED, 25, null, true, true),
          new Tariff("B", "B", Basis.FIXED, 25, null, true, true), new Tariff("C", "C", Basis.FIXED, 25, null, true, true));
      var r = TariffCalculator.calculate(new FlatProfile("X", null, null, "OCCUPIED"), three, List.of(),
          new GstPolicy(true, 1800, 0));
      assertThat(r.gstPaise()).isEqualTo(15);
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
      assertThatThrownBy(() -> Paise.times(Long.MAX_VALUE / 2, 3)).isInstanceOf(ArithmeticException.class);
      assertThatThrownBy(() -> Paise.percent(-1, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rupeesFormatting() {
      assertThat(Paise.rupees(425_050)).isEqualTo("4250.50");
      assertThat(Paise.rupees(5)).isEqualTo("0.05");
    }
  }

  @Nested
  class DueDatesAndLateFees {

    @Test
    void dueDateIsTheSocietyDayButAtLeastSevenDaysAway() {
      YearMonth oct = YearMonth.of(2026, 10);
      assertThat(DuesRules.dueDate(oct, 10, LocalDate.of(2026, 10, 1))).isEqualTo(LocalDate.of(2026, 10, 10));
      assertThat(DuesRules.dueDate(oct, 10, LocalDate.of(2026, 10, 5))).isEqualTo(LocalDate.of(2026, 10, 12));
      assertThat(DuesRules.dueDate(YearMonth.of(2027, 2), 28, LocalDate.of(2027, 1, 25)))
          .isEqualTo(LocalDate.of(2027, 2, 28));
      assertThat(DuesRules.period(oct)).isEqualTo("202610");
    }

    @Test
    void lateFeeOnlyAfterTheGracePeriod() {
      LocalDate due = LocalDate.of(2026, 10, 10);
      assertThat(DuesRules.isOverdue(due, due)).isFalse();
      assertThat(DuesRules.isOverdue(due, due.plusDays(1))).isTrue();
      assertThat(DuesRules.lateFeeDue(due, 5, due.plusDays(5))).isFalse();
      assertThat(DuesRules.lateFeeDue(due, 5, due.plusDays(6))).isTrue();
      assertThat(DuesRules.lateFeeDue(due, 0, due.plusDays(1))).isTrue();
      assertThat(DuesRules.daysOverdue(due, due.plusDays(6))).isEqualTo(6);
    }

    @Test
    void lateFeeAmounts() {
      assertThat(DuesRules.lateFee("FIXED", 10_000, 430_000)).isEqualTo(10_000);
      assertThat(DuesRules.lateFee("PERCENT", 200, 430_025)).isEqualTo(8_601); // 8600.5 → 8601
      assertThat(DuesRules.lateFee("PERCENT", 200, 0)).isZero();
      assertThat(DuesRules.lateFee("NONE", 200, 430_000)).isZero();
    }
  }

  @Nested
  class Bills {

    Bill published() {
      Bill b = new Bill(UUID.randomUUID(), UUID.randomUUID(), "A-1", "202610", LocalDate.of(2026, 10, 1),
          LocalDate.of(2026, 10, 10), 100_000, 18_000);
      b.publish("BILL-2026-000001", 0, Instant.now());
      return b;
    }

    @Test
    void paymentsMoveTheStatusAndNeverOverpayABill() {
      Bill b = published();
      assertThat(b.getStatus()).isEqualTo("DUE");
      assertThat(b.applyPayment(18_000)).isEqualTo(18_000);
      assertThat(b.getStatus()).isEqualTo("PART_PAID");
      assertThat(b.applyPayment(500_000)).isEqualTo(100_000);
      assertThat(b.getStatus()).isEqualTo("PAID");
      assertThat(b.getBalancePaise()).isZero();
    }

    @Test
    void lateFeeAndAdjustmentsKeepTheBalanceConsistent() {
      Bill b = published();
      b.applyLateFee(2_360, Instant.now());
      assertThatThrownBy(() -> b.applyLateFee(1, Instant.now())).isInstanceOf(IllegalStateException.class);
      b.adjust(-20_360);
      assertThat(b.getBalancePaise()).isEqualTo(100_000);
      assertThatThrownBy(() -> b.adjust(-100_001)).isInstanceOf(IllegalArgumentException.class);
      b.adjust(-100_000);
      assertThat(b.getStatus()).isEqualTo("PAID");
      b.adjust(500);
      assertThat(b.getStatus()).isEqualTo("DUE");
    }
  }

  @Nested
  class Journals {

    @Test
    void unbalancedJournalsAreRefused() {
      UUID flat = UUID.randomUUID();
      Journal ok = new Journal("BILL", UUID.randomUUID(), "x", LocalDate.now())
          .debit(Journal.Account.MEMBER_RECEIVABLE, flat, 118_000)
          .credit(Journal.Account.MAINTENANCE_INCOME, 100_000)
          .credit(Journal.Account.GST_PAYABLE, 18_000)
          .credit(Journal.Account.OTHER_INCOME, 0);
      assertThat(ok.lines()).hasSize(3);
      Journal bad = new Journal("BILL", UUID.randomUUID(), "x", LocalDate.now())
          .debit(Journal.Account.BANK, 1).credit(Journal.Account.MEMBER_RECEIVABLE, flat, 2);
      assertThatThrownBy(bad::lines).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void financialYearRunsAprilToMarch() {
      assertThat(FinancialYear.of(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
      assertThat(FinancialYear.of(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
      assertThat(FinancialYear.of(LocalDate.of(2099, 12, 1))).isEqualTo("2099-00");
      assertThat(FinancialYear.isValid("2026-27")).isTrue();
      assertThat(FinancialYear.isValid("2026-28")).isFalse();
    }
  }
}
