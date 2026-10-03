package in.societyos.vendor.purchasing.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * A purchase order. Approval is delegated to workflow-service:
 *
 * <pre>
 * DRAFT → SUBMITTED ─(workflow.instance.approved)→ APPROVED → PARTIALLY_RECEIVED → RECEIVED → CLOSED
 *            └─────(workflow.instance.rejected)→ REJECTED
 * DRAFT | SUBMITTED | APPROVED (nothing received) → CANCELLED
 * </pre>
 */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder extends TenantEntity {

  public enum Status { DRAFT, SUBMITTED, APPROVED, REJECTED, CANCELLED, PARTIALLY_RECEIVED, RECEIVED, CLOSED }

  @Column(nullable = false, updatable = false)
  private String number;
  @Column(name = "vendor_id", nullable = false, updatable = false)
  private UUID vendorId;
  @Column(name = "rfq_id", updatable = false)
  private UUID rfqId;
  @Column(name = "quote_id", updatable = false)
  private UUID quoteId;
  @Column(name = "store_id")
  private UUID storeId;
  @Column(nullable = false)
  private String title;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;
  @Column(name = "subtotal_paise", nullable = false)
  private long subtotalPaise;
  @Column(name = "tax_paise", nullable = false)
  private long taxPaise;
  @Column(name = "total_paise", nullable = false)
  private long totalPaise;
  @Column(name = "expected_on")
  private LocalDate expectedOn;
  @Column(name = "submitted_at")
  private Instant submittedAt;
  @Column(name = "workflow_instance_id")
  private UUID workflowInstanceId;
  @Column(name = "decided_at")
  private Instant decidedAt;
  @Column(name = "decided_by")
  private UUID decidedBy;
  @Column(name = "decision_comment", columnDefinition = "text")
  private String decisionComment;

  protected PurchaseOrder() {}

  public static PurchaseOrder draft(String number, UUID vendorId, String title, UUID storeId, LocalDate expectedOn,
      UUID rfqId, UUID quoteId) {
    if (title == null) {
      throw ProblemException.badRequest("INVALID_PO", "title is required");
    }
    PurchaseOrder po = new PurchaseOrder();
    po.getId();
    po.number = number;
    po.vendorId = vendorId;
    po.title = title;
    po.storeId = storeId;
    po.expectedOn = expectedOn;
    po.rfqId = rfqId;
    po.quoteId = quoteId;
    po.status = Status.DRAFT;
    return po;
  }

  /** Totals are always the sum of the lines. */
  public void totalsFrom(Collection<PoLine> lines) {
    long sub = 0;
    long tax = 0;
    for (PoLine l : lines) {
      sub = Math.addExact(sub, l.amountPaise());
      tax = Math.addExact(tax, l.taxPaise());
    }
    subtotalPaise = sub;
    taxPaise = tax;
    totalPaise = Math.addExact(sub, tax);
  }

  public void submit(int lineCount, Instant at) {
    if (status != Status.DRAFT) {
      throw ProblemException.unprocessable("INVALID_TRANSITION", "Only a draft PO can be submitted (is " + status + ")");
    }
    if (lineCount == 0 || totalPaise <= 0) {
      throw ProblemException.unprocessable("PO_EMPTY", "A PO needs at least one priced line");
    }
    status = Status.SUBMITTED;
    submittedAt = at;
  }

  public void approvalStarted(UUID instanceId) {
    if (status == Status.SUBMITTED && workflowInstanceId == null) {
      workflowInstanceId = instanceId;
    }
  }

  /**
   * Applies a workflow decision. Returns false when there is nothing to do (a redelivered or late
   * decision for a PO that is no longer waiting), so the caller publishes nothing.
   */
  public boolean decide(boolean approved, UUID instanceId, UUID by, String comment, Instant at) {
    if (status != Status.SUBMITTED) {
      return false;
    }
    if (instanceId != null) {
      workflowInstanceId = instanceId;
    }
    status = approved ? Status.APPROVED : Status.REJECTED;
    decidedBy = by;
    decidedAt = at;
    decisionComment = comment;
    return true;
  }

  public void cancel(boolean anythingReceived) {
    if (status != Status.DRAFT && status != Status.SUBMITTED && status != Status.APPROVED) {
      throw ProblemException.unprocessable("INVALID_TRANSITION", "A " + status + " PO cannot be cancelled");
    }
    if (anythingReceived) {
      throw ProblemException.unprocessable("PO_HAS_RECEIPTS", "Goods were already received against this PO");
    }
    status = Status.CANCELLED;
  }

  public void requireReceivable() {
    if (status != Status.APPROVED && status != Status.PARTIALLY_RECEIVED) {
      throw ProblemException.unprocessable("PO_NOT_RECEIVABLE", "Goods are received only on an approved PO (is " + status + ")");
    }
  }

  public void received(boolean allLinesReceived) {
    requireReceivable();
    status = allLinesReceived ? Status.RECEIVED : Status.PARTIALLY_RECEIVED;
  }

  /** Invoices are matched against approved POs that have at least one receipt. */
  public boolean isInvoiceable() {
    return status == Status.PARTIALLY_RECEIVED || status == Status.RECEIVED || status == Status.CLOSED
        || status == Status.APPROVED;
  }

  public void close() {
    if (status != Status.RECEIVED && status != Status.PARTIALLY_RECEIVED) {
      throw ProblemException.unprocessable("INVALID_TRANSITION", "Only a received PO can be closed");
    }
    status = Status.CLOSED;
  }

  public void requireDraft() {
    if (status != Status.DRAFT) {
      throw ProblemException.unprocessable("PO_NOT_DRAFT", "Only a draft PO can be edited");
    }
  }

  public String getNumber() { return number; }
  public UUID getVendorId() { return vendorId; }
  public UUID getRfqId() { return rfqId; }
  public UUID getQuoteId() { return quoteId; }
  public UUID getStoreId() { return storeId; }
  public String getTitle() { return title; }
  public Status getStatus() { return status; }
  public long getSubtotalPaise() { return subtotalPaise; }
  public long getTaxPaise() { return taxPaise; }
  public long getTotalPaise() { return totalPaise; }
  public LocalDate getExpectedOn() { return expectedOn; }
  public Instant getSubmittedAt() { return submittedAt; }
  public UUID getWorkflowInstanceId() { return workflowInstanceId; }
  public Instant getDecidedAt() { return decidedAt; }
  public UUID getDecidedBy() { return decidedBy; }
  public String getDecisionComment() { return decisionComment; }
}
