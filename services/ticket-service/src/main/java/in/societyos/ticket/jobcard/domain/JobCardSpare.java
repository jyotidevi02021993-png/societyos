package in.societyos.ticket.jobcard.domain;

import in.societyos.ticket.platform.core.error.ProblemException;
import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A spare requested for a job card. inventory-service confirms the issue with
 * {@code inventory.spare.issued}, which sets the unit cost; only issued spares count in the cost.
 */
@Entity
@Table(name = "job_card_spare")
public class JobCardSpare extends TenantEntity {

  public enum Status { REQUESTED, ISSUED, CANCELLED }

  @Column(name = "job_card_id", nullable = false, updatable = false)
  private UUID jobCardId;
  @Column(name = "spare_id", nullable = false, updatable = false)
  private UUID spareId;
  @Column(name = "store_id")
  private UUID storeId;
  @Column(nullable = false)
  private int qty;
  @Column(name = "unit_cost_paise", nullable = false)
  private long unitCostPaise;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;
  @Column(columnDefinition = "text")
  private String note;
  @Column(name = "requested_by", updatable = false)
  private UUID requestedBy;
  @Column(name = "issue_id")
  private UUID issueId;
  @Column(name = "issued_at")
  private Instant issuedAt;

  protected JobCardSpare() {}

  public static JobCardSpare requested(UUID jobCardId, UUID spareId, UUID storeId, int qty, String note,
      UUID requestedBy) {
    if (qty <= 0) {
      throw ProblemException.badRequest("INVALID_QTY", "qty must be positive");
    }
    JobCardSpare s = new JobCardSpare();
    s.jobCardId = jobCardId;
    s.spareId = spareId;
    s.storeId = storeId;
    s.qty = qty;
    s.note = note;
    s.requestedBy = requestedBy;
    s.status = Status.REQUESTED;
    return s;
  }

  /** An issue inventory made without a matching request (e.g. issued at the store counter). */
  public static JobCardSpare issuedDirectly(UUID jobCardId, UUID spareId, UUID storeId, int qty, long unitCostPaise,
      UUID issueId, Instant at) {
    JobCardSpare s = requested(jobCardId, spareId, storeId, qty, null, null);
    s.issued(issueId, storeId, qty, unitCostPaise, at);
    return s;
  }

  public void issued(UUID issue, UUID store, int issuedQty, long unitCost, Instant at) {
    if (status != Status.REQUESTED) {
      throw ProblemException.conflict("SPARE_NOT_REQUESTED", "This spare line is already " + status);
    }
    this.issueId = issue;
    if (store != null) this.storeId = store;
    this.qty = issuedQty;
    this.unitCostPaise = Math.max(0, unitCost);
    this.issuedAt = at;
    this.status = Status.ISSUED;
  }

  public void cancel() {
    if (status != Status.REQUESTED) {
      throw ProblemException.conflict("SPARE_NOT_REQUESTED", "Only a pending request can be cancelled");
    }
    status = Status.CANCELLED;
  }

  public long costPaise() {
    return status == Status.ISSUED ? unitCostPaise * qty : 0;
  }

  public UUID getJobCardId() { return jobCardId; }
  public UUID getSpareId() { return spareId; }
  public UUID getStoreId() { return storeId; }
  public int getQty() { return qty; }
  public long getUnitCostPaise() { return unitCostPaise; }
  public Status getStatus() { return status; }
  public String getNote() { return note; }
  public UUID getRequestedBy() { return requestedBy; }
  public UUID getIssueId() { return issueId; }
  public Instant getIssuedAt() { return issuedAt; }
}
