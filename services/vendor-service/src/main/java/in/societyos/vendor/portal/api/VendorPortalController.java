package in.societyos.vendor.portal.api;

import in.societyos.vendor.invoicing.api.InvoiceResponses.InvoiceRequest;
import in.societyos.vendor.invoicing.api.InvoiceResponses.InvoiceResponse;
import in.societyos.vendor.invoicing.api.InvoiceResponses.InvoiceSummary;
import in.societyos.vendor.invoicing.api.InvoiceResponses.LineRequest;
import in.societyos.vendor.invoicing.application.InvoiceService;
import in.societyos.vendor.invoicing.application.InvoiceService.InvoiceCommand;
import in.societyos.vendor.purchasing.api.PoResponses.PoResponse;
import in.societyos.vendor.purchasing.api.PoResponses.PoSummary;
import in.societyos.vendor.purchasing.application.PurchaseOrderService;
import in.societyos.vendor.rfq.api.RfqResponses.PortalQuoteRequest;
import in.societyos.vendor.rfq.api.RfqResponses.QuoteLineRequest;
import in.societyos.vendor.rfq.api.RfqResponses.QuoteResponse;
import in.societyos.vendor.rfq.api.RfqResponses.RfqResponse;
import in.societyos.vendor.rfq.api.RfqResponses.RfqSummary;
import in.societyos.vendor.rfq.application.RfqService;
import in.societyos.vendor.rfq.application.RfqService.QuoteCommand;
import in.societyos.vendor.vendor.application.VendorService;
import in.societyos.vendor.vendor.domain.Vendor;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendor portal (VENDOR role). The caller's vendor is resolved from their agent record; every
 * query is scoped to that vendor and anything else answers 404. Drafts and other vendors' quotes are
 * never shown. Vendors see their own invoices and payments but no other society finance data.
 */
@RestController
class VendorPortalController {

  static final String PORTAL = "@perm.hasAny('invoice:submit', 'amc:visit', 'quote:submit')";

  private final VendorService vendors;
  private final PurchaseOrderService orders;
  private final InvoiceService invoices;
  private final RfqService rfqs;

  VendorPortalController(VendorService vendors, PurchaseOrderService orders, InvoiceService invoices, RfqService rfqs) {
    this.vendors = vendors;
    this.orders = orders;
    this.invoices = invoices;
    this.rfqs = rfqs;
  }

  record PortalVendor(UUID id, String code, String name, String category, List<String> workScopes, String status,
      Double rating) {}

  @GetMapping("/v1/vendor-portal/me")
  @PreAuthorize(PORTAL)
  PortalVendor me() {
    Vendor v = vendors.portalVendor();
    return new PortalVendor(v.getId(), v.getCode(), v.getName(), v.getCategory(), v.getWorkScopes(), v.getStatus(),
        v.rating());
  }

  @GetMapping("/v1/vendor-portal/purchase-orders")
  @PreAuthorize(PORTAL)
  List<PoSummary> purchaseOrders() {
    return orders.listForVendor(vendors.portalVendor().getId()).stream().map(PoSummary::from).toList();
  }

  @GetMapping("/v1/vendor-portal/purchase-orders/{id}")
  @PreAuthorize(PORTAL)
  PoResponse purchaseOrder(@PathVariable UUID id) {
    return PoResponse.from(orders.getForVendor(id, vendors.portalVendor().getId()));
  }

  @GetMapping("/v1/vendor-portal/invoices")
  @PreAuthorize(PORTAL)
  List<InvoiceSummary> invoices() {
    return invoices.list(null, vendors.portalVendor().getId(), null).stream().map(InvoiceSummary::from).toList();
  }

  @GetMapping("/v1/vendor-portal/invoices/{id}")
  @PreAuthorize(PORTAL)
  InvoiceResponse invoice(@PathVariable UUID id) {
    return InvoiceResponse.from(invoices.getForVendor(id, vendors.portalVendor().getId()));
  }

  @PostMapping("/v1/vendor-portal/invoices")
  @PreAuthorize("@perm.has('invoice:submit')")
  @ResponseStatus(HttpStatus.CREATED)
  InvoiceResponse submitInvoice(@Valid @RequestBody InvoiceRequest r) {
    UUID vendorId = vendors.portalVendor().getId();
    return InvoiceResponse.from(invoices.submit(new InvoiceCommand(r.poId(), r.vendorInvoiceNo(), r.invoiceDate(),
        r.totalPaise(), r.mediaId(), r.lines().stream().map(LineRequest::command).toList()), vendorId));
  }

  @GetMapping("/v1/vendor-portal/rfqs")
  @PreAuthorize(PORTAL)
  List<RfqSummary> rfqs() {
    return rfqs.openForVendor(vendors.portalVendor().getId()).stream().map(RfqSummary::from).toList();
  }

  @GetMapping("/v1/vendor-portal/rfqs/{id}")
  @PreAuthorize(PORTAL)
  RfqResponse rfq(@PathVariable UUID id) {
    return RfqResponse.from(rfqs.getForVendor(id, vendors.portalVendor().getId()));
  }

  @PostMapping("/v1/vendor-portal/rfqs/{id}/quotes")
  @PreAuthorize("@perm.hasAny('quote:submit', 'invoice:submit')")
  @ResponseStatus(HttpStatus.CREATED)
  QuoteResponse quote(@PathVariable UUID id, @Valid @RequestBody PortalQuoteRequest r) {
    UUID vendorId = vendors.portalVendor().getId();
    return QuoteResponse.from(rfqs.submitQuote(id, new QuoteCommand(vendorId, r.validUntil(), r.deliveryDays(),
        r.notes(), r.lines().stream().map(QuoteLineRequest::command).toList()), true));
  }
}
