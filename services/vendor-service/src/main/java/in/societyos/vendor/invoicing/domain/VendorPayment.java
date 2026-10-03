package in.societyos.vendor.invoicing.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** A payment made to a vendor against an approved invoice. Financial rows are never deleted. */
@Entity
@Table(name = "vendor_payment")
public class VendorPayment extends TenantEntity {

  public static final Set<String> MODES = Set.of("NEFT", "RTGS", "UPI", "CHEQUE", "CASH", "OTHER");

  @Column(name = "invoice_id", nullable = false, updatable = false)
  private UUID invoiceId;
  @Column(name = "amount_paise", nullable = false, updatable = false)
  private long amountPaise;
  @Column(name = "paid_on", nullable = false, updatable = false)
  private LocalDate paidOn;
  @Column(nullable = false, updatable = false)
  private String mode;
  @Column(updatable = false)
  private String reference;

  protected VendorPayment() {}

  public static VendorPayment of(UUID invoiceId, long amountPaise, LocalDate paidOn, String mode, String reference) {
    if (mode == null || !MODES.contains(mode)) {
      throw ProblemException.badRequest("INVALID_MODE", "mode is one of " + MODES);
    }
    if (paidOn == null) {
      throw ProblemException.badRequest("INVALID_DATE", "paidOn is required");
    }
    VendorPayment p = new VendorPayment();
    p.invoiceId = invoiceId;
    p.amountPaise = amountPaise;
    p.paidOn = paidOn;
    p.mode = mode;
    p.reference = reference;
    return p;
  }

  public UUID getInvoiceId() { return invoiceId; }
  public long getAmountPaise() { return amountPaise; }
  public LocalDate getPaidOn() { return paidOn; }
  public String getMode() { return mode; }
  public String getReference() { return reference; }
}
