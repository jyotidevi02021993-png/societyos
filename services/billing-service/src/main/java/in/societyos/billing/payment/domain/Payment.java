package in.societyos.billing.payment.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Money received from a flat. ONLINE payments start PENDING with a gateway order and settle on the
 * gateway webhook (or reconciliation); offline payments (cash, cheque, UPI reference, bank
 * transfer) are recorded by accounts as SUCCEEDED.
 */
@Entity
@Table(name = "payment")
public class Payment extends TenantEntity {

  public static final Set<String> OFFLINE_METHODS = Set.of("CASH", "CHEQUE", "UPI", "BANK_TRANSFER");

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(name = "bill_id")
  private UUID billId;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(nullable = false)
  private String method;

  private String gateway;

  @Column(name = "gateway_order_id")
  private String gatewayOrderId;

  @Column(name = "gateway_payment_id")
  private String gatewayPaymentId;

  private String reference;

  @Column(nullable = false)
  private String status = "PENDING";

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "paid_at")
  private Instant paidAt;

  protected Payment() {}

  private Payment(UUID flatId, UUID billId, long amountPaise, String method) {
    super(in.societyos.billing.platform.core.UuidV7.next());
    if (amountPaise <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    this.flatId = flatId;
    this.billId = billId;
    this.amountPaise = amountPaise;
    this.method = method;
  }

  public static Payment online(UUID flatId, UUID billId, long amountPaise, String gateway) {
    Payment p = new Payment(flatId, billId, amountPaise, "ONLINE");
    p.gateway = gateway;
    return p;
  }

  public static Payment offline(UUID flatId, UUID billId, long amountPaise, String method, String reference,
      Instant paidAt) {
    if (!OFFLINE_METHODS.contains(method)) {
      throw new IllegalArgumentException("not an offline method: " + method);
    }
    Payment p = new Payment(flatId, billId, amountPaise, method);
    p.reference = reference;
    p.status = "SUCCEEDED";
    p.paidAt = paidAt;
    return p;
  }

  public void orderCreated(String orderId) {
    this.gatewayOrderId = orderId;
  }

  public boolean isPending() {
    return "PENDING".equals(status);
  }

  public boolean isSucceeded() {
    return "SUCCEEDED".equals(status);
  }

  /** Gateway capture. The captured amount is what was actually received. */
  public void succeed(String gatewayPaymentId, long capturedPaise, Instant at) {
    if (!isPending()) {
      throw new IllegalStateException("payment is " + status);
    }
    if (capturedPaise > 0) {
      this.amountPaise = capturedPaise;
    }
    this.gatewayPaymentId = gatewayPaymentId;
    this.status = "SUCCEEDED";
    this.paidAt = at;
  }

  public void fail(String reason) {
    if (!isPending()) {
      throw new IllegalStateException("payment is " + status);
    }
    this.status = "FAILED";
    this.failureReason = reason == null ? "UNKNOWN" : reason;
  }

  public UUID getFlatId() { return flatId; }
  public UUID getBillId() { return billId; }
  public long getAmountPaise() { return amountPaise; }
  public String getMethod() { return method; }
  public String getGateway() { return gateway; }
  public String getGatewayOrderId() { return gatewayOrderId; }
  public String getGatewayPaymentId() { return gatewayPaymentId; }
  public String getReference() { return reference; }
  public String getStatus() { return status; }
  public String getFailureReason() { return failureReason; }
  public Instant getPaidAt() { return paidAt; }
}
