package in.societyos.vendor.invoicing.domain;

import in.societyos.vendor.common.Amounts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Three-way match of a vendor invoice against its purchase order (what was ordered, at what price)
 * and its GRNs (what was actually accepted). Pure rules, no I/O:
 *
 * <ul>
 *   <li>{@code QTY_EXCEEDS_RECEIVED}: this invoice plus earlier non-rejected invoices bill more than
 *       the accepted GRN quantity of the line;
 *   <li>{@code PRICE_ABOVE_PO}: the unit price is above the PO price plus the tolerance;
 *   <li>{@code TAX_MISMATCH}: the line tax differs from GST at the PO rate by more than the rounding tolerance;
 *   <li>{@code TOTAL_MISMATCH}: the invoice total the vendor stated differs from the sum of its lines;
 *   <li>{@code DUPLICATE_LINE}, {@code UNKNOWN_PO_LINE}: the invoice itself is malformed.
 * </ul>
 */
public final class ThreeWayMatch {

  /** Allowed difference per tax amount and on the total: rounding by the vendor's software. */
  public static final long ROUNDING_TOLERANCE_PAISE = 100;

  /** A PO line with what was accepted on GRNs and what earlier invoices already billed. */
  public record PoLineFacts(UUID poLineId, int lineNo, int orderedQty, long unitPricePaise, int gstPercent,
      int acceptedQty, int previouslyInvoicedQty) {}

  public record InvoiceLine(UUID poLineId, int qty, long unitPricePaise, long taxPaise) {}

  public record Result(List<String> issues, long subtotalPaise, long taxPaise, long totalPaise) {
    public boolean matched() {
      return issues.isEmpty();
    }
  }

  private final int priceToleranceBasisPoints;

  /** @param priceToleranceBasisPoints allowed price increase over the PO, in 1/100 of a percent (0 = exact) */
  public ThreeWayMatch(int priceToleranceBasisPoints) {
    if (priceToleranceBasisPoints < 0) {
      throw new IllegalArgumentException("tolerance must not be negative");
    }
    this.priceToleranceBasisPoints = priceToleranceBasisPoints;
  }

  public Result match(List<PoLineFacts> poLines, List<InvoiceLine> invoiceLines, Long statedTotalPaise) {
    List<String> issues = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    long subtotal = 0;
    long tax = 0;
    if (invoiceLines.isEmpty()) {
      issues.add("NO_LINES: the invoice has no lines");
    }
    for (InvoiceLine il : invoiceLines) {
      PoLineFacts po = poLines.stream().filter(p -> p.poLineId().equals(il.poLineId())).findFirst().orElse(null);
      if (po == null) {
        issues.add("UNKNOWN_PO_LINE: " + il.poLineId() + " is not on the PO");
        continue;
      }
      String at = "Line " + po.lineNo() + ": ";
      if (!seen.add(il.poLineId())) {
        issues.add("DUPLICATE_LINE: " + at + "billed twice on this invoice");
        continue;
      }
      long amount = Amounts.linePaise(il.qty(), il.unitPricePaise());
      subtotal = Math.addExact(subtotal, amount);
      tax = Math.addExact(tax, il.taxPaise());

      int billed = po.previouslyInvoicedQty() + il.qty();
      if (billed > po.acceptedQty()) {
        issues.add("QTY_EXCEEDS_RECEIVED: " + at + "billed " + billed + " (incl. " + po.previouslyInvoicedQty()
            + " earlier) but " + po.acceptedQty() + " accepted on GRNs");
      }
      if (il.unitPricePaise() > maxPrice(po.unitPricePaise())) {
        issues.add("PRICE_ABOVE_PO: " + at + "unit price " + il.unitPricePaise() + " paise, PO price "
            + po.unitPricePaise() + " paise");
      }
      long expectedTax = Amounts.gstPaise(amount, po.gstPercent());
      if (Math.abs(expectedTax - il.taxPaise()) > ROUNDING_TOLERANCE_PAISE) {
        issues.add("TAX_MISMATCH: " + at + "tax " + il.taxPaise() + " paise, expected " + expectedTax + " paise at "
            + po.gstPercent() + "% GST");
      }
    }
    long total = Math.addExact(subtotal, tax);
    if (statedTotalPaise != null && Math.abs(statedTotalPaise - total) > ROUNDING_TOLERANCE_PAISE) {
      issues.add("TOTAL_MISMATCH: stated total " + statedTotalPaise + " paise, lines add up to " + total + " paise");
    }
    return new Result(List.copyOf(issues), subtotal, tax, total);
  }

  long maxPrice(long poPrice) {
    return poPrice + Math.multiplyExact(poPrice, priceToleranceBasisPoints) / 10_000;
  }
}
