package in.societyos.vendor.invoicing.api;

import in.societyos.vendor.invoicing.application.InvoiceService.InvoiceDetail;
import in.societyos.vendor.invoicing.application.InvoiceService.LineCommand;
import in.societyos.vendor.invoicing.domain.VendorInvoice;
import in.societyos.vendor.invoicing.domain.VendorInvoiceLine;
import in.societyos.vendor.invoicing.domain.VendorPayment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Invoice requests and responses, shared by the staff API and the vendor portal. */
public final class InvoiceResponses {

  private InvoiceResponses() {}

  public record LineRequest(@NotNull UUID poLineId, @Positive int qty, @PositiveOrZero long unitPricePaise,
      @PositiveOrZero Long taxPaise) {
    public LineCommand command() {
      return new LineCommand(poLineId, qty, unitPricePaise, taxPaise);
    }
  }

  public record InvoiceRequest(@NotNull UUID poId, @NotBlank String vendorInvoiceNo, @NotNull LocalDate invoiceDate,
      Long totalPaise, UUID mediaId, @NotEmpty List<@Valid LineRequest> lines) {}

  public record InvoiceSummary(UUID id, String number, String vendorInvoiceNo, UUID vendorId, UUID poId,
      LocalDate invoiceDate, long totalPaise, long paidPaise, String status) {
    public static InvoiceSummary from(VendorInvoice i) {
      return new InvoiceSummary(i.getId(), i.getNumber(), i.getVendorInvoiceNo(), i.getVendorId(), i.getPoId(),
          i.getInvoiceDate(), i.getTotalPaise(), i.getPaidPaise(), i.getStatus().name());
    }
  }

  public record InvoiceLineResponse(UUID id, UUID poLineId, int qty, long unitPricePaise, long amountPaise,
      long taxPaise) {
    static InvoiceLineResponse from(VendorInvoiceLine l) {
      return new InvoiceLineResponse(l.getId(), l.getPoLineId(), l.getQty(), l.getUnitPricePaise(), l.amountPaise(),
          l.getTaxPaise());
    }
  }

  public record PaymentResponse(UUID id, long amountPaise, LocalDate paidOn, String mode, String reference) {
    static PaymentResponse from(VendorPayment p) {
      return new PaymentResponse(p.getId(), p.getAmountPaise(), p.getPaidOn(), p.getMode(), p.getReference());
    }
  }

  public record InvoiceResponse(UUID id, String number, String vendorInvoiceNo, UUID vendorId, UUID poId,
      String poNumber, LocalDate invoiceDate, long subtotalPaise, long taxPaise, long totalPaise, long paidPaise,
      long outstandingPaise, UUID mediaId, String status, List<String> matchIssues, Instant matchedAt,
      Instant decidedAt, String decisionComment, List<InvoiceLineResponse> lines, List<PaymentResponse> payments) {
    public static InvoiceResponse from(InvoiceDetail d) {
      VendorInvoice i = d.invoice();
      return new InvoiceResponse(i.getId(), i.getNumber(), i.getVendorInvoiceNo(), i.getVendorId(), i.getPoId(),
          d.poNumber(), i.getInvoiceDate(), i.getSubtotalPaise(), i.getTaxPaise(), i.getTotalPaise(), i.getPaidPaise(),
          i.outstandingPaise(), i.getMediaId(), i.getStatus().name(), i.getMatchIssues(), i.getMatchedAt(),
          i.getDecidedAt(), i.getDecisionComment(), d.lines().stream().map(InvoiceLineResponse::from).toList(),
          d.payments().stream().map(PaymentResponse::from).toList());
    }
  }
}
