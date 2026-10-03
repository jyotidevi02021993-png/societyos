package in.societyos.vendor.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.vendor.invoicing.domain.ThreeWayMatch;
import in.societyos.vendor.invoicing.domain.ThreeWayMatch.InvoiceLine;
import in.societyos.vendor.invoicing.domain.ThreeWayMatch.PoLineFacts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ThreeWayMatchTest {

  static final UUID L1 = UUID.randomUUID();
  static final UUID L2 = UUID.randomUUID();

  /** 10 pumps ordered at 1,000.00 with 18% GST; 6 accepted on GRNs so far. Filter: 4 at 250.00, 12%. */
  static final List<PoLineFacts> PO = List.of(
      new PoLineFacts(L1, 1, 10, 100_000, 18, 6, 0),
      new PoLineFacts(L2, 2, 4, 25_000, 12, 4, 0));

  final ThreeWayMatch exact = new ThreeWayMatch(0);

  @Test
  void matchesWhenQuantityPriceAndTaxAgree() {
    var r = exact.match(PO, List.of(new InvoiceLine(L1, 6, 100_000, 108_000), new InvoiceLine(L2, 4, 25_000, 12_000)),
        820_000L);
    assertThat(r.matched()).as(r.issues().toString()).isTrue();
    assertThat(r.subtotalPaise()).isEqualTo(700_000);
    assertThat(r.taxPaise()).isEqualTo(120_000);
    assertThat(r.totalPaise()).isEqualTo(820_000);
  }

  @Test
  void billingMoreThanReceivedIsAMismatchEvenIfOrdered() {
    var r = exact.match(PO, List.of(new InvoiceLine(L1, 8, 100_000, 144_000)), null);
    assertThat(r.matched()).isFalse();
    assertThat(r.issues()).singleElement().asString().startsWith("QTY_EXCEEDS_RECEIVED").contains("8").contains("6 accepted");
  }

  @Test
  void earlierInvoicesCountTowardsTheReceivedQuantity() {
    var po = List.of(new PoLineFacts(L1, 1, 10, 100_000, 18, 6, 4));
    assertThat(exact.match(po, List.of(new InvoiceLine(L1, 2, 100_000, 36_000)), null).matched()).isTrue();
    assertThat(exact.match(po, List.of(new InvoiceLine(L1, 3, 100_000, 54_000)), null).issues())
        .anyMatch(i -> i.startsWith("QTY_EXCEEDS_RECEIVED"));
  }

  @Test
  void priceAbovePoIsFlaggedUnlessWithinTolerance() {
    var line = List.of(new InvoiceLine(L1, 1, 101_000, 18_180));
    assertThat(exact.match(PO, line, null).issues()).singleElement().asString().startsWith("PRICE_ABOVE_PO");
    // 1% tolerance (100 bp) allows 1,010.00
    assertThat(new ThreeWayMatch(100).match(PO, line, null).matched()).isTrue();
    // A lower price is always fine
    assertThat(exact.match(PO, List.of(new InvoiceLine(L1, 1, 90_000, 16_200)), null).matched()).isTrue();
  }

  @Test
  void taxMustBeGstAtThePoRateWithinRounding() {
    assertThat(exact.match(PO, List.of(new InvoiceLine(L1, 1, 100_000, 18_050)), null).matched()).isTrue();
    assertThat(exact.match(PO, List.of(new InvoiceLine(L1, 1, 100_000, 28_000)), null).issues())
        .singleElement().asString().startsWith("TAX_MISMATCH");
  }

  @Test
  void statedTotalMustAddUp() {
    var r = exact.match(PO, List.of(new InvoiceLine(L1, 1, 100_000, 18_000)), 200_000L);
    assertThat(r.issues()).singleElement().asString().startsWith("TOTAL_MISMATCH");
  }

  @Test
  void unknownAndDuplicateLinesAreRejected() {
    var r = exact.match(PO, List.of(new InvoiceLine(UUID.randomUUID(), 1, 1, 0), new InvoiceLine(L2, 1, 25_000, 3_000),
        new InvoiceLine(L2, 1, 25_000, 3_000)), null);
    assertThat(r.issues()).anyMatch(i -> i.startsWith("UNKNOWN_PO_LINE")).anyMatch(i -> i.startsWith("DUPLICATE_LINE"));
    assertThat(exact.match(PO, List.of(), null).matched()).isFalse();
  }

  @Test
  void toleranceCannotBeNegative() {
    assertThatThrownBy(() -> new ThreeWayMatch(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
