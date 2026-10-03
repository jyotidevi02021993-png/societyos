package in.societyos.billing.payment.domain;

import in.societyos.billing.common.BillingEvent;
import java.time.Instant;
import java.util.UUID;

/** Payment events, exactly as in contracts/events/CATALOGUE.md (billing section). */
public final class PaymentEvents {

  private PaymentEvents() {}

  /** {@code refType} BILL (refId = bill id) or ON_ACCOUNT (refId = flat id, no bill named). */
  public record PaymentSucceeded(UUID paymentId, UUID billId, UUID flatId, long amountPaise, String method,
      Instant paidAt, String refType, UUID refId) implements BillingEvent {
    @Override public String type() { return "billing.payment.succeeded"; }
    @Override public UUID aggregateId() { return paymentId; }

    public static PaymentSucceeded of(Payment p) {
      boolean bill = p.getBillId() != null;
      return new PaymentSucceeded(p.getId(), p.getBillId(), p.getFlatId(), p.getAmountPaise(), p.getMethod(),
          p.getPaidAt(), bill ? "BILL" : "ON_ACCOUNT", bill ? p.getBillId() : p.getFlatId());
    }
  }

  public record PaymentFailed(UUID paymentId, UUID billId, UUID flatId, long amountPaise, String reason)
      implements BillingEvent {
    @Override public String type() { return "billing.payment.failed"; }
    @Override public UUID aggregateId() { return paymentId; }
  }

  public record ReceiptIssued(UUID receiptId, UUID paymentId, UUID flatId, String number) implements BillingEvent {
    @Override public String type() { return "billing.receipt.issued"; }
    @Override public UUID aggregateId() { return receiptId; }
  }
}
