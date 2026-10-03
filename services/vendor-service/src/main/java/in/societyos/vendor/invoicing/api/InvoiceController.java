package in.societyos.vendor.invoicing.api;

import in.societyos.vendor.invoicing.api.InvoiceResponses.InvoiceRequest;
import in.societyos.vendor.invoicing.api.InvoiceResponses.InvoiceResponse;
import in.societyos.vendor.invoicing.api.InvoiceResponses.InvoiceSummary;
import in.societyos.vendor.invoicing.application.InvoiceService;
import in.societyos.vendor.invoicing.application.InvoiceService.InvoiceCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vendor invoices as entered by staff (vendors use {@code /v1/vendor-portal/invoices}). Each
 * submission is 3-way matched against the PO and its GRNs; only MATCHED invoices can be approved.
 */
@RestController
class InvoiceController {

  static final String VIEW = "@perm.hasAny('vendor:view', 'invoice:approve', 'vendorpayment:record', 'po:create')";

  private final InvoiceService invoices;

  InvoiceController(InvoiceService invoices) {
    this.invoices = invoices;
  }

  record DecisionRequest(String comment) {}

  record PaymentRequest(@Positive long amountPaise, @NotNull LocalDate paidOn, @NotBlank String mode,
      String reference) {}

  @GetMapping("/v1/invoices")
  @PreAuthorize(VIEW)
  List<InvoiceSummary> list(@RequestParam(required = false) String status,
      @RequestParam(required = false) UUID vendorId, @RequestParam(required = false) UUID poId) {
    return invoices.list(status, vendorId, poId).stream().map(InvoiceSummary::from).toList();
  }

  @PostMapping("/v1/invoices")
  @PreAuthorize("@perm.hasAny('invoice:record', 'invoice:approve')")
  @ResponseStatus(HttpStatus.CREATED)
  InvoiceResponse submit(@Valid @RequestBody InvoiceRequest r) {
    return InvoiceResponse.from(invoices.submit(new InvoiceCommand(r.poId(), r.vendorInvoiceNo(), r.invoiceDate(),
        r.totalPaise(), r.mediaId(), r.lines().stream().map(InvoiceResponses.LineRequest::command).toList()), null));
  }

  @GetMapping("/v1/invoices/{id}")
  @PreAuthorize(VIEW)
  InvoiceResponse get(@PathVariable UUID id) {
    return InvoiceResponse.from(invoices.get(id));
  }

  @PostMapping("/v1/invoices/{id}/rematch")
  @PreAuthorize("@perm.hasAny('invoice:record', 'invoice:approve')")
  InvoiceResponse rematch(@PathVariable UUID id) {
    return InvoiceResponse.from(invoices.rematch(id));
  }

  @PostMapping("/v1/invoices/{id}/approve")
  @PreAuthorize("@perm.has('invoice:approve')")
  InvoiceResponse approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest r) {
    return InvoiceResponse.from(invoices.approve(id, r == null ? null : r.comment()));
  }

  @PostMapping("/v1/invoices/{id}/reject")
  @PreAuthorize("@perm.has('invoice:approve')")
  InvoiceResponse reject(@PathVariable UUID id, @RequestBody DecisionRequest r) {
    return InvoiceResponse.from(invoices.reject(id, r.comment()));
  }

  @PostMapping("/v1/invoices/{id}/payments")
  @PreAuthorize("@perm.has('vendorpayment:record')")
  @ResponseStatus(HttpStatus.CREATED)
  InvoiceResponse pay(@PathVariable UUID id, @Valid @RequestBody PaymentRequest r) {
    return InvoiceResponse.from(invoices.recordPayment(id, r.amountPaise(), r.paidOn(), r.mode(), r.reference()));
  }
}
