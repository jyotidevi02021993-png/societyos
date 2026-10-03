package in.societyos.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.vendor.common.Amounts;
import in.societyos.vendor.invoicing.domain.ThreeWayMatch;
import in.societyos.vendor.invoicing.domain.VendorInvoice;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.purchasing.domain.PoLine;
import in.societyos.vendor.purchasing.domain.PurchaseOrder;
import in.societyos.vendor.rfq.domain.Quote;
import in.societyos.vendor.rfq.domain.QuoteComparison;
import in.societyos.vendor.rfq.domain.QuoteLine;
import in.societyos.vendor.rfq.domain.RfqLine;
import in.societyos.vendor.vendor.domain.Vendor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementRulesTest {

  static final UUID VENDOR = UUID.randomUUID();

  static PurchaseOrder po() {
    return PurchaseOrder.draft("PO-2026-000001", VENDOR, "Pumps", null, null, null, null);
  }

  static PoLine line(PurchaseOrder po, int qty) {
    return PoLine.of(po.getId(), 1, "PUMP-1HP", null, "Pump", qty, "NOS", 100_000, 18);
  }

  @Test
  void gstRoundsHalfUpToThePaisa() {
    assertThat(Amounts.gstPaise(100_000, 18)).isEqualTo(18_000);
    assertThat(Amounts.gstPaise(333, 18)).isEqualTo(60); // 59.94
    assertThat(Amounts.gstPaise(25, 18)).isEqualTo(5); // 4.5 → 5
  }

  @Test
  void poTotalsComeFromLinesAndOnlyDraftsAreSubmitted() {
    PurchaseOrder po = po();
    po.totalsFrom(List.of(line(po, 3)));
    assertThat(po.getTotalPaise()).isEqualTo(354_000);
    po.submit(1, Instant.now());
    assertThat(po.getStatus()).isEqualTo(PurchaseOrder.Status.SUBMITTED);
    assertThatThrownBy(() -> po.submit(1, Instant.now())).isInstanceOf(ProblemException.class);

    PurchaseOrder empty = po();
    assertThatThrownBy(() -> empty.submit(0, Instant.now())).hasMessageContaining("priced line");
  }

  @Test
  void workflowDecisionIsAppliedOnceAndLateDecisionsAreIgnored() {
    PurchaseOrder po = po();
    po.totalsFrom(List.of(line(po, 1)));
    po.submit(1, Instant.now());
    assertThat(po.decide(true, UUID.randomUUID(), null, "ok", Instant.now())).isTrue();
    assertThat(po.getStatus()).isEqualTo(PurchaseOrder.Status.APPROVED);
    assertThat(po.decide(false, UUID.randomUUID(), null, "late", Instant.now())).isFalse();
    assertThat(po.getStatus()).isEqualTo(PurchaseOrder.Status.APPROVED);
  }

  @Test
  void goodsAreReceivedOnlyOnApprovedPosAndNeverBeyondTheOrder() {
    PurchaseOrder po = po();
    PoLine l = line(po, 5);
    po.totalsFrom(List.of(l));
    assertThatThrownBy(po::requireReceivable).hasMessageContaining("approved");
    po.submit(1, Instant.now());
    po.decide(true, null, null, null, Instant.now());

    l.receive(3);
    po.received(l.fullyReceived());
    assertThat(po.getStatus()).isEqualTo(PurchaseOrder.Status.PARTIALLY_RECEIVED);
    assertThatThrownBy(() -> l.receive(3)).hasMessageContaining("only 2 pending");
    l.receive(2);
    po.received(l.fullyReceived());
    assertThat(po.getStatus()).isEqualTo(PurchaseOrder.Status.RECEIVED);
    assertThatThrownBy(() -> po.cancel(true)).isInstanceOf(ProblemException.class);
  }

  @Test
  void onlyMatchedInvoicesAreApprovedAndPaymentsNeverExceedTheTotal() {
    VendorInvoice inv = VendorInvoice.submitted("VINV-1", "INV-9", VENDOR, UUID.randomUUID(), LocalDate.now(), null);
    inv.matched(new ThreeWayMatch.Result(List.of("QTY_EXCEEDS_RECEIVED: x"), 100, 18, 118), Instant.now());
    assertThatThrownBy(() -> inv.approve(null, null, Instant.now())).hasMessageContaining("3-way matched");
    inv.matched(new ThreeWayMatch.Result(List.of(), 100_000, 18_000, 118_000), Instant.now());
    inv.approve(null, null, Instant.now());
    inv.paid(18_000);
    assertThat(inv.getStatus()).isEqualTo(VendorInvoice.Status.PARTIALLY_PAID);
    assertThatThrownBy(() -> inv.paid(100_001)).hasMessageContaining("outstanding");
    inv.paid(100_000);
    assertThat(inv.getStatus()).isEqualTo(VendorInvoice.Status.PAID);
  }

  @Test
  void quotesRankByTotalAndIncompleteOrExpiredOnesAreNotRanked() {
    UUID rfq = UUID.randomUUID();
    RfqLine a = RfqLine.of(rfq, 1, "A", null, "Item A", 2, "NOS");
    RfqLine b = RfqLine.of(rfq, 2, "B", null, "Item B", 1, "NOS");
    LocalDate today = LocalDate.of(2026, 9, 29);
    Quote cheap = quote(rfq, 1_000, null);
    Quote dear = quote(rfq, 5_000, null);
    Quote partial = quote(rfq, 500, null);
    Quote expired = quote(rfq, 800, today.minusDays(1));
    Map<UUID, List<QuoteLine>> lines = Map.of(
        cheap.getId(), List.of(QuoteLine.of(cheap.getId(), a.getId(), 300, 18), QuoteLine.of(cheap.getId(), b.getId(), 400, 18)),
        dear.getId(), List.of(QuoteLine.of(dear.getId(), a.getId(), 200, 18), QuoteLine.of(dear.getId(), b.getId(), 4_600, 18)),
        partial.getId(), List.of(QuoteLine.of(partial.getId(), a.getId(), 250, 18)),
        expired.getId(), List.of(QuoteLine.of(expired.getId(), a.getId(), 100, 18), QuoteLine.of(expired.getId(), b.getId(), 600, 18)));

    var r = QuoteComparison.compare(List.of(a, b), List.of(dear, cheap, partial, expired), lines, today);
    assertThat(r.quotes().stream().filter(q -> q.rank() != null).map(QuoteComparison.Ranked::quoteId))
        .containsExactly(cheap.getId(), dear.getId());
    assertThat(r.quotes().stream().filter(q -> q.quoteId().equals(partial.getId())).findFirst().orElseThrow().complete())
        .isFalse();
    assertThat(r.lines().getFirst().lowestQuoteId()).isEqualTo(expired.getId());
  }

  @Test
  void vendorValidatesGstinAndRating() {
    Vendor v = Vendor.register("V1", "Aqua Pumps", "PLUMBING");
    assertThatThrownBy(() -> v.describe("Aqua", "PLUMBING", List.of(), "BAD", null, null, null, null, null))
        .hasMessageContaining("GSTIN");
    v.rate(4);
    v.rate(5);
    assertThat(v.rating()).isEqualTo(4.5);
    assertThatThrownBy(() -> v.rate(6)).isInstanceOf(ProblemException.class);
    v.changeStatus("BLACKLISTED");
    assertThatThrownBy(v::requireActive).hasMessageContaining("BLACKLISTED");
  }

  static Quote quote(UUID rfq, long total, LocalDate validUntil) {
    Quote q = Quote.of(rfq, UUID.randomUUID(), validUntil, 7, null);
    q.totals(total, 0);
    return q;
  }
}
