package in.societyos.vendor.invoicing.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A vendor invoice against one PO, 3-way matched on submission:
 *
 * <pre>
 * (submit) → MATCHED ──approve──→ APPROVED → PARTIALLY_PAID → PAID
 *          → MISMATCH ─rematch─→ MATCHED     (after more goods are received)
 * MATCHED | MISMATCH ──reject──→ REJECTED
 * </pre>
 */
@Entity
@Table(name = "vendor_invoice")
public class VendorInvoice extends TenantEntity {

  public enum Status { MATCHED, MISMATCH, APPROVED, REJECTED, PARTIALLY_PAID, PAID }

  @Column(nullable = false, updatable = false)
  private String number;
  @Column(name = "vendor_invoice_no", nullable = false, updatable = false)
  private String vendorInvoiceNo;
  @Column(name = "vendor_id", nullable = false, updatable = false)
  private UUID vendorId;
  @Column(name = "po_id", nullable = false, updatable = false)
  private UUID poId;
  @Column(name = "invoice_date", nullable = false, updatable = false)
  private LocalDate invoiceDate;
  @Column(name = "subtotal_paise", nullable = false)
  private long subtotalPaise;
  @Column(name = "tax_paise", nullable = false)
  private long taxPaise;
  @Column(name = "total_paise", nullable = false)
  private long totalPaise;
  @Column(name = "paid_paise", nullable = false)
  private long paidPaise;
  @Column(name = "media_id")
  private UUID mediaId;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "match_issues", nullable = false, columnDefinition = "text[]")
  private String[] matchIssues = new String[0];
  @Column(name = "matched_at")
  private Instant matchedAt;
  @Column(name = "decided_at")
  private Instant decidedAt;
  @Column(name = "decided_by")
  private UUID decidedBy;
  @Column(name = "decision_comment", columnDefinition = "text")
  private String decisionComment;

  protected VendorInvoice() {}

  public static VendorInvoice submitted(String number, String vendorInvoiceNo, UUID vendorId, UUID poId,
      LocalDate invoiceDate, UUID mediaId) {
    if (vendorInvoiceNo == null || invoiceDate == null) {
      throw ProblemException.badRequest("INVALID_INVOICE", "vendorInvoiceNo and invoiceDate are required");
    }
    VendorInvoice i = new VendorInvoice();
    i.getId();
    i.number = number;
    i.vendorInvoiceNo = vendorInvoiceNo;
    i.vendorId = vendorId;
    i.poId = poId;
    i.invoiceDate = invoiceDate;
    i.mediaId = mediaId;
    i.status = Status.MISMATCH;
    return i;
  }

  /** Records a match result; only a new or mismatched invoice can be (re)matched. */
  public void matched(ThreeWayMatch.Result r, Instant at) {
    if (status != Status.MISMATCH && status != Status.MATCHED) {
      throw ProblemException.unprocessable("INVALID_TRANSITION", "A " + status + " invoice is not re-matched");
    }
    subtotalPaise = r.subtotalPaise();
    taxPaise = r.taxPaise();
    totalPaise = r.totalPaise();
    matchIssues = r.issues().toArray(String[]::new);
    matchedAt = at;
    status = r.matched() ? Status.MATCHED : Status.MISMATCH;
  }

  public void approve(UUID by, String comment, Instant at) {
    if (status != Status.MATCHED) {
      throw ProblemException.unprocessable("INVOICE_NOT_MATCHED", "Only a 3-way matched invoice can be approved (is "
          + status + ")");
    }
    decide(Status.APPROVED, by, comment, at);
  }

  public void reject(UUID by, String comment, Instant at) {
    if (status != Status.MATCHED && status != Status.MISMATCH) {
      throw ProblemException.unprocessable("INVALID_TRANSITION", "A " + status + " invoice cannot be rejected");
    }
    decide(Status.REJECTED, by, comment, at);
  }

  private void decide(Status to, UUID by, String comment, Instant at) {
    status = to;
    decidedBy = by;
    decidedAt = at;
    decisionComment = comment;
  }

  public long outstandingPaise() {
    return totalPaise - paidPaise;
  }

  /** A payment against an approved invoice; overpaying is refused. */
  public void paid(long amountPaise) {
    if (status != Status.APPROVED && status != Status.PARTIALLY_PAID) {
      throw ProblemException.unprocessable("INVOICE_NOT_APPROVED", "Payments are recorded on approved invoices only");
    }
    if (amountPaise <= 0) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "amountPaise must be positive");
    }
    if (amountPaise > outstandingPaise()) {
      throw ProblemException.unprocessable("OVERPAYMENT", "Only " + outstandingPaise() + " paise is outstanding");
    }
    paidPaise += amountPaise;
    status = paidPaise == totalPaise ? Status.PAID : Status.PARTIALLY_PAID;
  }

  /** Counts towards billed quantities: everything except a rejected invoice. */
  public boolean isLive() {
    return status != Status.REJECTED;
  }

  public String getNumber() { return number; }
  public String getVendorInvoiceNo() { return vendorInvoiceNo; }
  public UUID getVendorId() { return vendorId; }
  public UUID getPoId() { return poId; }
  public LocalDate getInvoiceDate() { return invoiceDate; }
  public long getSubtotalPaise() { return subtotalPaise; }
  public long getTaxPaise() { return taxPaise; }
  public long getTotalPaise() { return totalPaise; }
  public long getPaidPaise() { return paidPaise; }
  public UUID getMediaId() { return mediaId; }
  public Status getStatus() { return status; }
  public List<String> getMatchIssues() { return List.of(matchIssues); }
  public Instant getMatchedAt() { return matchedAt; }
  public Instant getDecidedAt() { return decidedAt; }
  public UUID getDecidedBy() { return decidedBy; }
  public String getDecisionComment() { return decisionComment; }
}
