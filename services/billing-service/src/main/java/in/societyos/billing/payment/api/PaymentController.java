package in.societyos.billing.payment.api;

import in.societyos.billing.payment.application.PaymentService;
import in.societyos.billing.payment.domain.Payment;
import in.societyos.billing.payment.domain.PaymentAllocation;
import in.societyos.billing.payment.domain.Receipt;
import in.societyos.billing.platform.web.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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

@RestController
class PaymentController {

  private final PaymentService payments;

  PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  record PayRequest(@Positive Long amountPaise) {}

  record PayResponse(UUID paymentId, String gateway, String orderId, String keyId, String checkoutUrl,
      long amountPaise, String status) {}

  record OfflineRequest(UUID flatId, UUID billId, @Positive long amountPaise, @NotBlank String method,
      @Size(max = 100) String reference, LocalDate paidOn) {}

  record AllocationResponse(UUID billId, long amountPaise) {
    static AllocationResponse from(PaymentAllocation a) {
      return new AllocationResponse(a.getBillId(), a.getAmountPaise());
    }
  }

  record ReceiptResponse(UUID id, UUID paymentId, UUID flatId, String flatLabel, String number, long amountPaise,
      String method, String reference, Instant issuedAt) {
    static ReceiptResponse from(Receipt r) {
      return r == null ? null : new ReceiptResponse(r.getId(), r.getPaymentId(), r.getFlatId(), r.getFlatLabel(),
          r.getNumber(), r.getAmountPaise(), r.getMethod(), r.getReference(), r.getIssuedAt());
    }
  }

  record PaymentResponse(UUID id, UUID flatId, UUID billId, long amountPaise, String method, String gateway,
      String gatewayOrderId, String reference, String status, String failureReason, Instant paidAt,
      List<AllocationResponse> allocations, ReceiptResponse receipt) {
    static PaymentResponse from(Payment p, List<PaymentAllocation> allocations, Receipt receipt) {
      return new PaymentResponse(p.getId(), p.getFlatId(), p.getBillId(), p.getAmountPaise(), p.getMethod(),
          p.getGateway(), p.getGatewayOrderId(), p.getReference(), p.getStatus(), p.getFailureReason(), p.getPaidAt(),
          allocations == null ? null : allocations.stream().map(AllocationResponse::from).toList(),
          ReceiptResponse.from(receipt));
    }
  }

  /** Starts an online payment: returns the gateway order for the hosted checkout. */
  @PostMapping("/v1/bills/{id}/pay")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@perm.has('bill:pay')")
  PayResponse pay(@PathVariable UUID id, @Valid @RequestBody(required = false) PayRequest r) {
    PaymentService.OnlineStart s = payments.startOnline(id, r == null ? null : r.amountPaise());
    return new PayResponse(s.payment().getId(), s.payment().getGateway(), s.order().orderId(), s.order().keyId(),
        s.order().checkoutUrl(), s.payment().getAmountPaise(), s.payment().getStatus());
  }

  /** Cash, cheque, UPI reference or bank transfer received by accounts. */
  @PostMapping("/v1/payments")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@perm.has('payment:record')")
  PaymentResponse record(@Valid @RequestBody OfflineRequest r) {
    PaymentService.PaymentView v = payments.recordOffline(r.flatId(), r.billId(), r.amountPaise(), r.method(),
        r.reference(), r.paidOn());
    return PaymentResponse.from(v.payment(), v.allocations(), v.receipt());
  }

  @GetMapping("/v1/payments")
  @PreAuthorize("@perm.hasAny('payment:view', 'payment:record', 'bill:view', 'bill:view-own')")
  List<PaymentResponse> list(@RequestParam(required = false) UUID flatId, @RequestParam(required = false) Integer limit) {
    return payments.list(flatId, CursorPage.clampLimit(limit)).stream()
        .map(p -> PaymentResponse.from(p, null, null)).toList();
  }

  @GetMapping("/v1/payments/{id}")
  @PreAuthorize("@perm.hasAny('payment:view', 'payment:record', 'bill:view', 'bill:view-own')")
  PaymentResponse get(@PathVariable UUID id) {
    PaymentService.PaymentView v = payments.get(id);
    return PaymentResponse.from(v.payment(), v.allocations(), v.receipt());
  }

  @GetMapping("/v1/receipts")
  @PreAuthorize("@perm.hasAny('payment:view', 'payment:record', 'bill:view', 'bill:view-own')")
  List<ReceiptResponse> receipts(@RequestParam(required = false) UUID flatId,
      @RequestParam(required = false) Integer limit) {
    return payments.receipts(flatId, CursorPage.clampLimit(limit)).stream().map(ReceiptResponse::from).toList();
  }

  @GetMapping("/v1/receipts/{id}")
  @PreAuthorize("@perm.hasAny('payment:view', 'payment:record', 'bill:view', 'bill:view-own')")
  ReceiptResponse receipt(@PathVariable UUID id) {
    return ReceiptResponse.from(payments.receipt(id));
  }
}
