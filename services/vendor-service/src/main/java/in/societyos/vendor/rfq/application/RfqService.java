package in.societyos.vendor.rfq.application;

import in.societyos.vendor.common.Texts;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.DocumentNumberService;
import in.societyos.vendor.purchasing.application.PurchaseOrderService;
import in.societyos.vendor.purchasing.application.PurchaseOrderService.PoCommand;
import in.societyos.vendor.purchasing.application.PurchaseOrderService.PoDetail;
import in.societyos.vendor.rfq.domain.Quote;
import in.societyos.vendor.rfq.domain.QuoteComparison;
import in.societyos.vendor.rfq.domain.QuoteLine;
import in.societyos.vendor.rfq.domain.Rfq;
import in.societyos.vendor.rfq.domain.RfqLine;
import in.societyos.vendor.rfq.infrastructure.QuoteLineRepository;
import in.societyos.vendor.rfq.infrastructure.QuoteRepository;
import in.societyos.vendor.rfq.infrastructure.RfqLineRepository;
import in.societyos.vendor.rfq.infrastructure.RfqRepository;
import in.societyos.vendor.vendor.application.VendorService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RFQs, vendor quotes, their comparison and the award that turns the chosen quote into a draft PO. */
@Service
public class RfqService {

  public record RfqLineCommand(String itemCode, UUID spareId, String description, int qty, String unit) {}

  public record RfqCommand(String title, String description, UUID storeId, List<UUID> invitedVendorIds, LocalDate dueOn,
      List<RfqLineCommand> lines) {}

  public record QuoteLineCommand(UUID rfqLineId, long unitPricePaise, Integer gstPercent) {}

  public record QuoteCommand(UUID vendorId, LocalDate validUntil, Integer deliveryDays, String notes,
      List<QuoteLineCommand> lines) {}

  public record RfqDetail(Rfq rfq, List<RfqLine> lines, List<Quote> quotes) {}

  private final RfqRepository rfqs;
  private final RfqLineRepository rfqLines;
  private final QuoteRepository quotes;
  private final QuoteLineRepository quoteLines;
  private final VendorService vendors;
  private final PurchaseOrderService orders;
  private final DocumentNumberService numbers;
  private final Clock clock;
  private final ZoneId zone;

  public RfqService(RfqRepository rfqs, RfqLineRepository rfqLines, QuoteRepository quotes,
      QuoteLineRepository quoteLines, VendorService vendors, PurchaseOrderService orders,
      DocumentNumberService numbers, Clock clock, @Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    this.rfqs = rfqs;
    this.rfqLines = rfqLines;
    this.quotes = quotes;
    this.quoteLines = quoteLines;
    this.vendors = vendors;
    this.orders = orders;
    this.numbers = numbers;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  @Transactional
  public RfqDetail create(RfqCommand c) {
    if (c.lines() == null || c.lines().isEmpty()) {
      throw ProblemException.badRequest("RFQ_EMPTY", "An RFQ needs at least one line");
    }
    List<UUID> invited = c.invitedVendorIds() == null ? List.of() : c.invitedVendorIds();
    invited.forEach(v -> vendors.require(v).requireActive());
    Rfq rfq = Rfq.open(numbers.next("RFQ"), Texts.clean(c.title()), Texts.clean(c.description()), c.storeId(), invited,
        c.dueOn());
    List<RfqLine> lines = new ArrayList<>();
    int no = 1;
    for (RfqLineCommand l : c.lines()) {
      lines.add(RfqLine.of(rfq.getId(), no++, Texts.code(l.itemCode()), l.spareId(), Texts.clean(l.description()),
          l.qty(), Texts.upper(l.unit())));
    }
    rfqs.save(rfq);
    rfqLines.saveAll(lines);
    return new RfqDetail(rfq, lines, List.of());
  }

  /**
   * Records a quote. From the vendor portal {@code asVendor} is true: the vendor must be invited.
   * Staff may enter a quote received on paper for any active vendor.
   */
  @Transactional
  public Quote submitQuote(UUID rfqId, QuoteCommand c, boolean asVendor) {
    Rfq rfq = rfqs.findForUpdate(rfqId).orElseThrow(() -> ProblemException.notFound("rfq", rfqId));
    rfq.requireOpen();
    vendors.require(c.vendorId()).requireActive();
    if (asVendor && !rfq.isInvited(c.vendorId())) {
      throw ProblemException.notFound("rfq", rfqId);
    }
    if (quotes.existsByRfqIdAndVendorId(rfqId, c.vendorId())) {
      throw ProblemException.conflict("QUOTE_EXISTS", "This vendor already quoted for " + rfq.getNumber());
    }
    Map<UUID, RfqLine> lines = rfqLines.findByRfqIdOrderByLineNoAsc(rfqId).stream()
        .collect(Collectors.toMap(RfqLine::getId, l -> l));
    if (c.lines() == null || c.lines().isEmpty()) {
      throw ProblemException.badRequest("QUOTE_EMPTY", "A quote prices at least one line");
    }
    Quote q = Quote.of(rfqId, c.vendorId(), c.validUntil(), c.deliveryDays(), Texts.clean(c.notes()));
    List<QuoteLine> priced = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    long sub = 0;
    long tax = 0;
    for (QuoteLineCommand l : c.lines()) {
      RfqLine rl = lines.get(l.rfqLineId());
      if (rl == null) {
        throw ProblemException.badRequest("UNKNOWN_RFQ_LINE", "Line " + l.rfqLineId() + " is not on this RFQ");
      }
      if (!seen.add(rl.getId())) {
        throw ProblemException.badRequest("DUPLICATE_LINE", "RFQ line " + rl.getLineNo() + " is priced twice");
      }
      QuoteLine ql = QuoteLine.of(q.getId(), rl.getId(), l.unitPricePaise(), l.gstPercent() == null ? 18 : l.gstPercent());
      sub = Math.addExact(sub, ql.amountPaise(rl.getQty()));
      tax = Math.addExact(tax, ql.taxPaise(rl.getQty()));
      priced.add(ql);
    }
    q.totals(sub, tax);
    quotes.save(q);
    quoteLines.saveAll(priced);
    return q;
  }

  @Transactional(readOnly = true)
  public QuoteComparison.Result compare(UUID rfqId) {
    RfqDetail d = get(rfqId);
    Map<UUID, List<QuoteLine>> byQuote = quoteLines.findByQuoteIdIn(d.quotes().stream().map(Quote::getId).toList())
        .stream().collect(Collectors.groupingBy(QuoteLine::getQuoteId));
    return QuoteComparison.compare(d.lines(), d.quotes(), byQuote, LocalDate.now(clock.withZone(zone)));
  }

  /** Awards the RFQ to one complete, unexpired quote and raises a draft PO at the quoted prices. */
  @Transactional
  public PoDetail award(UUID rfqId, UUID quoteId) {
    Rfq rfq = rfqs.findForUpdate(rfqId).orElseThrow(() -> ProblemException.notFound("rfq", rfqId));
    Quote winner = quotes.findById(quoteId).filter(q -> q.getRfqId().equals(rfqId))
        .orElseThrow(() -> ProblemException.notFound("quote", quoteId));
    if (winner.isExpired(LocalDate.now(clock.withZone(zone)))) {
      throw ProblemException.unprocessable("QUOTE_EXPIRED", "The quote expired on " + winner.getValidUntil());
    }
    List<RfqLine> lines = rfqLines.findByRfqIdOrderByLineNoAsc(rfqId);
    Map<UUID, QuoteLine> prices = quoteLines.findByQuoteId(quoteId).stream()
        .collect(Collectors.toMap(QuoteLine::getRfqLineId, l -> l));
    if (!lines.stream().allMatch(l -> prices.containsKey(l.getId()))) {
      throw ProblemException.unprocessable("QUOTE_INCOMPLETE", "The quote does not price every line");
    }
    rfq.award(quoteId);
    for (Quote q : quotes.findByRfqIdOrderByTotalPaiseAsc(rfqId)) {
      if (q.getId().equals(quoteId)) q.accept(); else q.reject();
      quotes.save(q);
    }
    rfqs.save(rfq);
    List<PurchaseOrderService.LineCommand> po = lines.stream().map(l -> {
      QuoteLine p = prices.get(l.getId());
      return new PurchaseOrderService.LineCommand(l.getItemCode(), l.getSpareId(), l.getDescription(), l.getQty(),
          l.getUnit(), p.getUnitPricePaise(), p.getGstPercent());
    }).toList();
    return orders.create(new PoCommand(winner.getVendorId(), rfq.getTitle(), rfq.getStoreId(), null, rfqId, quoteId, po));
  }

  @Transactional
  public Rfq cancel(UUID rfqId) {
    Rfq rfq = rfqs.findForUpdate(rfqId).orElseThrow(() -> ProblemException.notFound("rfq", rfqId));
    rfq.cancel();
    return rfqs.save(rfq);
  }

  @Transactional(readOnly = true)
  public List<Rfq> list(String status) {
    if (status == null) {
      return rfqs.findTop200ByOrderByCreatedAtDesc();
    }
    try {
      return rfqs.findTop200ByStatusOrderByCreatedAtDesc(Rfq.Status.valueOf(Texts.upper(status)));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_STATUS", "Unknown RFQ status " + status);
    }
  }

  /** Vendor portal: open RFQs this vendor was invited to. */
  @Transactional(readOnly = true)
  public List<Rfq> openForVendor(UUID vendorId) {
    return rfqs.findTop200ByStatusOrderByCreatedAtDesc(Rfq.Status.OPEN).stream().filter(r -> r.isInvited(vendorId))
        .toList();
  }

  @Transactional(readOnly = true)
  public RfqDetail get(UUID id) {
    Rfq rfq = rfqs.findById(id).orElseThrow(() -> ProblemException.notFound("rfq", id));
    return new RfqDetail(rfq, rfqLines.findByRfqIdOrderByLineNoAsc(id), quotes.findByRfqIdOrderByTotalPaiseAsc(id));
  }

  /** Vendor portal: an RFQ the vendor was invited to, with only the vendor's own quote. */
  @Transactional(readOnly = true)
  public RfqDetail getForVendor(UUID id, UUID vendorId) {
    RfqDetail d = get(id);
    if (!d.rfq().isInvited(vendorId)) {
      throw ProblemException.notFound("rfq", id);
    }
    return new RfqDetail(d.rfq(), d.lines(), d.quotes().stream().filter(q -> q.getVendorId().equals(vendorId)).toList());
  }
}
