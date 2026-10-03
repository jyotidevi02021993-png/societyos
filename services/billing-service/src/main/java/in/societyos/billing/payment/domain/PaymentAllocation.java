package in.societyos.billing.payment.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** The part of a payment that settled one bill. */
@Entity
@Table(name = "payment_allocation")
public class PaymentAllocation extends TenantEntity {

  @Column(name = "payment_id", nullable = false)
  private UUID paymentId;

  @Column(name = "bill_id", nullable = false)
  private UUID billId;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  protected PaymentAllocation() {}

  public PaymentAllocation(UUID paymentId, UUID billId, long amountPaise) {
    this.paymentId = paymentId;
    this.billId = billId;
    this.amountPaise = amountPaise;
  }

  public UUID getPaymentId() { return paymentId; }
  public UUID getBillId() { return billId; }
  public long getAmountPaise() { return amountPaise; }
}
