package in.societyos.vendor.invoicing.application;

import in.societyos.vendor.common.Texts;
import in.societyos.vendor.invoicing.domain.InvoiceApproved;
import in.societyos.vendor.invoicing.domain.ThreeWayMatch;
import in.societyos.vendor.invoicing.domain.VendorInvoice;
import in.societyos.vendor.invoicing.domain.VendorInvoiceLine;
import in.societyos.vendor.invoicing.domain.VendorPayment;
import in.societyos.vendor.invoicing.infrastructure.VendorInvoiceLineRepository;
import in.societyos.vendor.invoicing.infrastructure.VendorInvoiceRepository;
import in.societyos.vendor.invoicing.infrastructure.VendorPaymentRepository;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.core.tenant.TenantContext;
import in.societyos.vendor.platform.events.DomainEvents;
import in.societyos.vendor.platform.jpa.DocumentNumberService;
import in.societyos.vendor.purchasing.application.PurchaseOrderService;
import in.societyos.vendor.purchasing.domain.PoLine;
import in.societyos.vendor.purchasing.domain.PurchaseOrder;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vendor invoices: submission with 3-way match, approval, rejection and payments. */
@Service
public class InvoiceService {

  public record LineCommand(UUID poLineId, int qty, long unitPricePaise, Long taxPaise) {}

  public record InvoiceCommand(UUID poId, String vendorInvoiceNo, LocalDate invoiceDate, Long totalPaise, UUID mediaId,
      List<LineCommand> lines) {}

  public record InvoiceDetail(VendorInvoice invoice, String poNumber, List<VendorInvoiceLine> lines,
      List<VendorPayment> payments) {}

  private final VendorInvoiceRepository invoices;
  private final VendorInvoiceLineRepository invoiceLines;
  private final VendorPaymentRepository payments;
  private final PurchaseOrderService orders;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final Clock clock;
  private final ThreeWayMatch match;

  public InvoiceService(VendorInvoiceRepository invoices, VendorInvoiceLineRepository invoiceLines,
      VendorPaymentRepository payments, PurchaseOrderService orders, DocumentNumberService numbers,
      DomainEvents events, Clock clock,
      @Value("${sos.vendor.match.price-tolerance-bp:0}") int priceToleranceBasisPoints) {
    this.invoices = invoices;
    this.invoiceLines = invoiceLines;
    this.payments = payments;
    this.orders = orders;
    this.numbers = numbers;
    this.events = events;
    this.clock = clock;
    this.match = new ThreeWayMatch(priceToleranceBasisPoints);
  }

  /**
   * Submits an invoice for the PO's vendor. {@code asVendorId} is the portal caller's vendor (the
   * PO must be theirs) or null when staff enter the invoice. Always matched immediately.
   */
  @Transactional
  public InvoiceDetail submit(InvoiceCommand c, UUID asVendorId) {
    PurchaseOrder po = orders.forUpdate(c.poId());
    if (asVendorId != null && !po.getVendorId().equals(asVendorId)) {
      throw ProblemException.notFound("purchase_order", c.poId());
    }
    if (!po.isInvoiceable()) {
      throw ProblemException.unprocessable("PO_NOT_INVOICEABLE", "PO " + po.getNumber() + " is " + po.getStatus());
    }
    String vendorNo = Texts.code(c.vendorInvoiceNo());
    if (vendorNo != null && invoices.existsByVendorIdAndVendorInvoiceNo(po.getVendorId(), vendorNo)) {
      throw ProblemException.conflict("DUPLICATE_INVOICE", "Invoice " + vendorNo + " was already submitted");
    }
    if (c.lines() == null || c.lines().isEmpty()) {
      throw ProblemException.badRequest("INVOICE_EMPTY", "An invoice needs at least one line");
    }
    VendorInvoice inv = VendorInvoice.submitted(numbers.next("VINV"), vendorNo, po.getVendorId(), po.getId(),
        c.invoiceDate(), c.mediaId());
    List<PoLine> poLines = orders.lines(po.getId());
    List<VendorInvoiceLine> lines = c.lines().stream().map(l -> VendorInvoiceLine.of(inv.getId(), l.poLineId(), l.qty(),
        l.unitPricePaise(), l.taxPaise() != null ? l.taxPaise() : defaultTax(poLines, l))).toList();
    inv.matched(runMatch(inv, poLines, lines, c.totalPaise()), clock.instant());
    invoices.save(inv);
    invoiceLines.saveAll(lines);
    return detail(inv, po.getNumber(), lines);
  }

  /** Re-runs the match, typically after another GRN arrived for a mismatched invoice. */
  @Transactional
  public InvoiceDetail rematch(UUID id) {
    VendorInvoice inv = forUpdate(id);
    PurchaseOrder po = orders.forUpdate(inv.getPoId());
    List<VendorInvoiceLine> lines = invoiceLines.findByInvoiceId(id);
    inv.matched(runMatch(inv, orders.lines(po.getId()), lines, inv.getTotalPaise()), clock.instant());
    invoices.save(inv);
    return detail(inv, po.getNumber(), lines);
  }

  @Transactional
  public InvoiceDetail approve(UUID id, String comment) {
    VendorInvoice inv = forUpdate(id);
    inv.approve(TenantContext.userId().orElse(null), Texts.clean(comment), clock.instant());
    invoices.save(inv);
    events.publish(InvoiceApproved.of(inv));
    return get(id);
  }

  @Transactional
  public InvoiceDetail reject(UUID id, String comment) {
    VendorInvoice inv = forUpdate(id);
    if (Texts.clean(comment) == null) {
      throw ProblemException.badRequest("REASON_REQUIRED", "A reason is required to reject an invoice");
    }
    inv.reject(TenantContext.userId().orElse(null), Texts.clean(comment), clock.instant());
    invoices.save(inv);
    return get(id);
  }

  @Transactional
  public InvoiceDetail recordPayment(UUID id, long amountPaise, LocalDate paidOn, String mode, String reference) {
    VendorInvoice inv = forUpdate(id);
    VendorPayment p = VendorPayment.of(id, amountPaise, paidOn, Texts.upper(mode), Texts.clean(reference));
    inv.paid(amountPaise);
    payments.save(p);
    invoices.save(inv);
    return get(id);
  }

  @Transactional(readOnly = true)
  public List<VendorInvoice> list(String status, UUID vendorId, UUID poId) {
    if (poId != null) {
      return invoices.findByPoIdOrderByCreatedAtAsc(poId);
    }
    if (vendorId != null) {
      return invoices.findTop200ByVendorIdOrderByCreatedAtDesc(vendorId);
    }
    if (status != null) {
      try {
        return invoices.findTop200ByStatusOrderByCreatedAtDesc(VendorInvoice.Status.valueOf(Texts.upper(status)));
      } catch (IllegalArgumentException e) {
        throw ProblemException.badRequest("INVALID_STATUS", "Unknown invoice status " + status);
      }
    }
    return invoices.findTop200ByOrderByCreatedAtDesc();
  }

  @Transactional(readOnly = true)
  public InvoiceDetail get(UUID id) {
    VendorInvoice inv = invoices.findById(id).orElseThrow(() -> ProblemException.notFound("invoice", id));
    return detail(inv, orders.require(inv.getPoId()).getNumber(), invoiceLines.findByInvoiceId(id));
  }

  /** Vendor portal: an invoice of this vendor, else 404. */
  @Transactional(readOnly = true)
  public InvoiceDetail getForVendor(UUID id, UUID vendorId) {
    InvoiceDetail d = get(id);
    if (!d.invoice().getVendorId().equals(vendorId)) {
      throw ProblemException.notFound("invoice", id);
    }
    return d;
  }

  // --- helpers ----------------------------------------------------------------------------

  private ThreeWayMatch.Result runMatch(VendorInvoice inv, List<PoLine> poLines, List<VendorInvoiceLine> lines,
      Long statedTotal) {
    List<ThreeWayMatch.PoLineFacts> facts = poLines.stream().map(pl -> new ThreeWayMatch.PoLineFacts(pl.getId(),
        pl.getLineNo(), pl.getQty(), pl.getUnitPricePaise(), pl.getGstPercent(), pl.getReceivedQty(),
        (int) invoiceLines.billedQty(pl.getId(), inv.getId()))).toList();
    return match.match(facts, lines.stream().map(VendorInvoiceLine::toMatch).toList(), statedTotal);
  }

  /** When the vendor gives no tax for a line: GST at the PO line's rate. */
  private static long defaultTax(List<PoLine> poLines, LineCommand l) {
    int rate = poLines.stream().filter(p -> p.getId().equals(l.poLineId())).findFirst().map(PoLine::getGstPercent)
        .orElse(0);
    return in.societyos.vendor.common.Amounts.gstPaise(Math.multiplyExact(l.qty(), l.unitPricePaise()), rate);
  }

  private VendorInvoice forUpdate(UUID id) {
    return invoices.findForUpdate(id).orElseThrow(() -> ProblemException.notFound("invoice", id));
  }

  private InvoiceDetail detail(VendorInvoice inv, String poNumber, List<VendorInvoiceLine> lines) {
    return new InvoiceDetail(inv, poNumber, lines, payments.findByInvoiceIdOrderByCreatedAtAsc(inv.getId()));
  }
}
