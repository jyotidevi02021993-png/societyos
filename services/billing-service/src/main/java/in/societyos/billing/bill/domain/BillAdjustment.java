package in.societyos.billing.bill.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A credit note (CN-…) or debit note (DN-…) against a published bill. Locked on issue. */
@Entity
@Table(name = "bill_adjustment")
public class BillAdjustment extends TenantEntity {

  @Column(name = "bill_id", nullable = false)
  private UUID billId;

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(nullable = false)
  private String number;

  @Column(nullable = false)
  private String kind;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(nullable = false)
  private String reason;

  @Column(name = "locked_at", nullable = false)
  private Instant lockedAt;

  protected BillAdjustment() {}

  public BillAdjustment(UUID billId, UUID flatId, String number, String kind, long amountPaise, String reason,
      Instant at) {
    this.billId = billId;
    this.flatId = flatId;
    this.number = number;
    this.kind = kind;
    this.amountPaise = amountPaise;
    this.reason = reason;
    this.lockedAt = at;
  }

  public boolean isCredit() {
    return "CREDIT".equals(kind);
  }

  public UUID getBillId() { return billId; }
  public UUID getFlatId() { return flatId; }
  public String getNumber() { return number; }
  public String getKind() { return kind; }
  public long getAmountPaise() { return amountPaise; }
  public String getReason() { return reason; }
}
