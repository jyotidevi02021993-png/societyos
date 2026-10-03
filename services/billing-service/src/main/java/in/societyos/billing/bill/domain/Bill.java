package in.societyos.billing.bill.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One flat's bill for a period. Money is exact paise and always consistent (also a DB check):
 * {@code balance = total + lateFee + adjustments - paid}, where {@code total = amount + gst}.
 * Status: DRAFT (preview) → DUE → PART_PAID → PAID.
 */
@Entity
@Table(name = "bill")
public class Bill extends TenantEntity {

  @Column(name = "bill_run_id", nullable = false)
  private UUID billRunId;

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(name = "flat_label", nullable = false)
  private String flatLabel;

  private String number;

  @Column(nullable = false)
  private String period;

  @Column(name = "bill_date", nullable = false)
  private LocalDate billDate;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(name = "gst_paise", nullable = false)
  private long gstPaise;

  @Column(name = "total_paise", nullable = false)
  private long totalPaise;

  @Column(name = "late_fee_paise", nullable = false)
  private long lateFeePaise;

  @Column(name = "adjustment_paise", nullable = false)
  private long adjustmentPaise;

  @Column(name = "paid_paise", nullable = false)
  private long paidPaise;

  @Column(name = "balance_paise", nullable = false)
  private long balancePaise;

  @Column(name = "arrears_paise", nullable = false)
  private long arrearsPaise;

  @Column(nullable = false)
  private String status = "DRAFT";

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "reminder_sent_at")
  private Instant reminderSentAt;

  @Column(name = "overdue_notified_at")
  private Instant overdueNotifiedAt;

  @Column(name = "late_fee_applied_at")
  private Instant lateFeeAppliedAt;

  protected Bill() {}

  public Bill(UUID billRunId, UUID flatId, String flatLabel, String period, LocalDate billDate, LocalDate dueDate,
      long amountPaise, long gstPaise) {
    super(in.societyos.billing.platform.core.UuidV7.next());
    if (amountPaise < 0 || gstPaise < 0) {
      throw new IllegalArgumentException("negative bill amounts");
    }
    this.billRunId = billRunId;
    this.flatId = flatId;
    this.flatLabel = flatLabel;
    this.period = period;
    this.billDate = billDate;
    this.dueDate = dueDate;
    this.amountPaise = amountPaise;
    this.gstPaise = gstPaise;
    this.totalPaise = Math.addExact(amountPaise, gstPaise);
    recompute();
  }

  public boolean isOpen() {
    return "DUE".equals(status) || "PART_PAID".equals(status);
  }

  public boolean isPublished() {
    return !"DRAFT".equals(status);
  }

  public void publish(String number, long arrearsPaise, Instant at) {
    if (isPublished()) {
      throw new IllegalStateException("already published");
    }
    this.number = number;
    this.arrearsPaise = arrearsPaise;
    this.publishedAt = at;
    this.status = "DUE";
    recompute();
  }

  /** Applies up to {@code paise} of a payment; returns the amount actually applied. */
  public long applyPayment(long paise) {
    requireOpen();
    long applied = Math.min(paise, balancePaise);
    if (applied <= 0) {
      return 0;
    }
    paidPaise = Math.addExact(paidPaise, applied);
    recompute();
    return applied;
  }

  public void applyLateFee(long paise, Instant at) {
    requireOpen();
    if (lateFeeAppliedAt != null) {
      throw new IllegalStateException("late fee already applied");
    }
    lateFeePaise = Math.addExact(lateFeePaise, paise);
    lateFeeAppliedAt = at;
    recompute();
  }

  /** Credit note (negative) or debit note (positive). A credit never exceeds the balance. */
  public void adjust(long signedPaise) {
    if (!isPublished() || "CANCELLED".equals(status)) {
      throw new IllegalStateException("bill not published");
    }
    if (signedPaise < 0 && -signedPaise > balancePaise) {
      throw new IllegalArgumentException("credit exceeds the balance");
    }
    adjustmentPaise = Math.addExact(adjustmentPaise, signedPaise);
    recompute();
  }

  public void reminderSent(Instant at) {
    reminderSentAt = at;
  }

  public void overdueNotified(Instant at) {
    overdueNotifiedAt = at;
  }

  private void requireOpen() {
    if (!isOpen()) {
      throw new IllegalStateException("bill is not open: " + status);
    }
  }

  private void recompute() {
    balancePaise = Math.subtractExact(
        Math.addExact(Math.addExact(totalPaise, lateFeePaise), adjustmentPaise), paidPaise);
    if (balancePaise < 0) {
      throw new IllegalStateException("negative balance");
    }
    if (isPublished() && !"CANCELLED".equals(status)) {
      status = balancePaise == 0 ? "PAID" : paidPaise > 0 ? "PART_PAID" : "DUE";
    }
  }

  public UUID getBillRunId() { return billRunId; }
  public UUID getFlatId() { return flatId; }
  public String getFlatLabel() { return flatLabel; }
  public String getNumber() { return number; }
  public String getPeriod() { return period; }
  public LocalDate getBillDate() { return billDate; }
  public LocalDate getDueDate() { return dueDate; }
  public long getAmountPaise() { return amountPaise; }
  public long getGstPaise() { return gstPaise; }
  public long getTotalPaise() { return totalPaise; }
  public long getLateFeePaise() { return lateFeePaise; }
  public long getAdjustmentPaise() { return adjustmentPaise; }
  public long getPaidPaise() { return paidPaise; }
  public long getBalancePaise() { return balancePaise; }
  public long getArrearsPaise() { return arrearsPaise; }
  public String getStatus() { return status; }
  public Instant getPublishedAt() { return publishedAt; }
  public Instant getReminderSentAt() { return reminderSentAt; }
  public Instant getOverdueNotifiedAt() { return overdueNotifiedAt; }
  public Instant getLateFeeAppliedAt() { return lateFeeAppliedAt; }
}
