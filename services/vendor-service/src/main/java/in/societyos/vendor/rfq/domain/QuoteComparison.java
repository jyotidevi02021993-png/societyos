package in.societyos.vendor.rfq.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Side-by-side comparison of the quotes for one RFQ: quotes ranked by total (then delivery days),
 * and for each line the cheapest quoted unit price. Expired quotes are listed but not ranked.
 */
public final class QuoteComparison {

  private QuoteComparison() {}

  public record Ranked(UUID quoteId, UUID vendorId, long totalPaise, Integer deliveryDays, boolean expired,
      Integer rank, boolean complete) {}

  public record LinePrice(UUID quoteId, UUID vendorId, long unitPricePaise) {}

  public record LineComparison(UUID rfqLineId, int lineNo, String description, int qty, List<LinePrice> prices,
      UUID lowestQuoteId) {}

  public record Result(List<Ranked> quotes, List<LineComparison> lines) {}

  /** {@code linesByQuote}: quote id → its lines. A quote that prices every RFQ line is "complete". */
  public static Result compare(List<RfqLine> rfqLines, List<Quote> quotes, Map<UUID, List<QuoteLine>> linesByQuote,
      java.time.LocalDate today) {
    List<Quote> ordered = quotes.stream()
        .sorted(Comparator.comparingLong(Quote::getTotalPaise)
            .thenComparing(q -> q.getDeliveryDays() == null ? Integer.MAX_VALUE : q.getDeliveryDays()))
        .toList();
    List<Ranked> ranked = new ArrayList<>();
    int rank = 1;
    for (Quote q : ordered) {
      List<QuoteLine> ql = linesByQuote.getOrDefault(q.getId(), List.of());
      boolean complete = rfqLines.stream().allMatch(r -> ql.stream().anyMatch(l -> l.getRfqLineId().equals(r.getId())));
      boolean expired = q.isExpired(today);
      boolean eligible = complete && !expired && q.getStatus() != Quote.Status.REJECTED;
      ranked.add(new Ranked(q.getId(), q.getVendorId(), q.getTotalPaise(), q.getDeliveryDays(), expired,
          eligible ? rank++ : null, complete));
    }
    List<LineComparison> lines = new ArrayList<>();
    for (RfqLine r : rfqLines) {
      List<LinePrice> prices = quotes.stream()
          .map(q -> linesByQuote.getOrDefault(q.getId(), List.of()).stream()
              .filter(l -> l.getRfqLineId().equals(r.getId())).findFirst()
              .map(l -> new LinePrice(q.getId(), q.getVendorId(), l.getUnitPricePaise())).orElse(null))
          .filter(Objects::nonNull)
          .sorted(Comparator.comparingLong(LinePrice::unitPricePaise))
          .toList();
      lines.add(new LineComparison(r.getId(), r.getLineNo(), r.getDescription(), r.getQty(), prices,
          prices.isEmpty() ? null : prices.getFirst().quoteId()));
    }
    return new Result(ranked, lines);
  }
}
