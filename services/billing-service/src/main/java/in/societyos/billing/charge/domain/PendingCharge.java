package in.societyos.billing.charge.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A one-off charge waiting for the flat's next bill: a facility booking (from
 * {@code community.booking.confirmed}), a manual charge, or a recoverable cost. PENDING until a
 * bill run is published with it, then BILLED with the bill id.
 */
@Entity
@Table(name = "pending_charge")
public class PendingCharge extends TenantEntity {

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(name = "source_type", nullable = false)
  private String sourceType;

  @Column(name = "source_ref")
  private UUID sourceRef;

  @Column(nullable = false)
  private String description;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(name = "gst_applicable", nullable = false)
  private boolean gstApplicable;

  @Column(nullable = false)
  private String status = "PENDING";

  @Column(name = "bill_id")
  private UUID billId;

  protected PendingCharge() {}

  public PendingCharge(UUID flatId, String sourceType, UUID sourceRef, String description, long amountPaise,
      boolean gstApplicable) {
    if (amountPaise <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    this.flatId = flatId;
    this.sourceType = sourceType;
    this.sourceRef = sourceRef;
    this.description = description;
    this.amountPaise = amountPaise;
    this.gstApplicable = gstApplicable;
  }

  public boolean isPending() {
    return "PENDING".equals(status);
  }

  public void billed(UUID billId) {
    this.status = "BILLED";
    this.billId = billId;
  }

  public void cancel() {
    this.status = "CANCELLED";
  }

  public UUID getFlatId() { return flatId; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceRef() { return sourceRef; }
  public String getDescription() { return description; }
  public long getAmountPaise() { return amountPaise; }
  public boolean isGstApplicable() { return gstApplicable; }
  public String getStatus() { return status; }
  public UUID getBillId() { return billId; }
}
