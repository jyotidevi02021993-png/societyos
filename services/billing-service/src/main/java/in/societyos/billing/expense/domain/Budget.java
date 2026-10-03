package in.societyos.billing.expense.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A yearly budget for one expense category: DRAFT (editable) → APPROVED or REJECTED by a
 * {@code budget:approve} holder who is not its author (maker-checker).
 */
@Entity
@Table(name = "budget")
public class Budget extends TenantEntity {

  @Column(name = "financial_year", nullable = false)
  private String financialYear;

  @Column(nullable = false)
  private String category;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  private String notes;

  @Column(nullable = false)
  private String status = "DRAFT";

  @Column(name = "decided_by")
  private UUID decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decision_note")
  private String decisionNote;

  protected Budget() {}

  public Budget(String financialYear, String category, long amountPaise, String notes) {
    this.financialYear = financialYear;
    this.category = category;
    update(amountPaise, notes);
  }

  public boolean isDraft() {
    return "DRAFT".equals(status);
  }

  public void update(long amountPaise, String notes) {
    if (!isDraft()) {
      throw new IllegalStateException("only a draft budget can change");
    }
    if (amountPaise <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    this.amountPaise = amountPaise;
    this.notes = notes;
  }

  public void decide(boolean approve, UUID by, String note, Instant at) {
    if (!isDraft()) {
      throw new IllegalStateException("budget already decided");
    }
    this.status = approve ? "APPROVED" : "REJECTED";
    this.decidedBy = by;
    this.decisionNote = note;
    this.decidedAt = at;
  }

  public String getFinancialYear() { return financialYear; }
  public String getCategory() { return category; }
  public long getAmountPaise() { return amountPaise; }
  public String getNotes() { return notes; }
  public String getStatus() { return status; }
  public UUID getDecidedBy() { return decidedBy; }
  public Instant getDecidedAt() { return decidedAt; }
  public String getDecisionNote() { return decisionNote; }
}
