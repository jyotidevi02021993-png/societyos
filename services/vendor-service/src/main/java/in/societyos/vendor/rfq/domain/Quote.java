package in.societyos.vendor.rfq.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** A vendor's quotation for an RFQ; one per vendor per RFQ. */
@Entity
@Table(name = "quote")
public class Quote extends TenantEntity {

  public enum Status { SUBMITTED, ACCEPTED, REJECTED }

  @Column(name = "rfq_id", nullable = false, updatable = false)
  private UUID rfqId;
  @Column(name = "vendor_id", nullable = false, updatable = false)
  private UUID vendorId;
  @Column(name = "valid_until")
  private LocalDate validUntil;
  @Column(name = "delivery_days")
  private Integer deliveryDays;
  @Column(columnDefinition = "text")
  private String notes;
  @Column(name = "subtotal_paise", nullable = false)
  private long subtotalPaise;
  @Column(name = "tax_paise", nullable = false)
  private long taxPaise;
  @Column(name = "total_paise", nullable = false)
  private long totalPaise;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.SUBMITTED;

  protected Quote() {}

  public static Quote of(UUID rfqId, UUID vendorId, LocalDate validUntil, Integer deliveryDays, String notes) {
    if (deliveryDays != null && deliveryDays < 0) {
      throw ProblemException.badRequest("INVALID_DELIVERY", "deliveryDays must not be negative");
    }
    Quote q = new Quote();
    q.getId();
    q.rfqId = rfqId;
    q.vendorId = vendorId;
    q.validUntil = validUntil;
    q.deliveryDays = deliveryDays;
    q.notes = notes;
    return q;
  }

  public void totals(long subtotal, long tax) {
    subtotalPaise = subtotal;
    taxPaise = tax;
    totalPaise = Math.addExact(subtotal, tax);
  }

  public void accept() {
    status = Status.ACCEPTED;
  }

  public void reject() {
    status = Status.REJECTED;
  }

  public boolean isExpired(LocalDate today) {
    return validUntil != null && validUntil.isBefore(today);
  }

  public UUID getRfqId() { return rfqId; }
  public UUID getVendorId() { return vendorId; }
  public LocalDate getValidUntil() { return validUntil; }
  public Integer getDeliveryDays() { return deliveryDays; }
  public String getNotes() { return notes; }
  public long getSubtotalPaise() { return subtotalPaise; }
  public long getTaxPaise() { return taxPaise; }
  public long getTotalPaise() { return totalPaise; }
  public Status getStatus() { return status; }
}
