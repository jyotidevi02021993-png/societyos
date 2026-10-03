package in.societyos.billing.bill.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A month's billing for the whole society: PREVIEW (draft bills, can be discarded or recomputed)
 * then PUBLISHED (numbered bills, ledger posted, locked).
 */
@Entity
@Table(name = "bill_run")
public class BillRun extends TenantEntity {

  @Column(nullable = false)
  private String period;

  @Column(nullable = false)
  private String status = "PREVIEW";

  @Column(name = "bill_date", nullable = false)
  private LocalDate billDate;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Column(name = "bill_count", nullable = false)
  private int billCount;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(name = "gst_paise", nullable = false)
  private long gstPaise;

  @Column(name = "total_paise", nullable = false)
  private long totalPaise;

  @Column(nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String warnings = "[]";

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "published_by")
  private UUID publishedBy;

  @Column(name = "locked_at")
  private Instant lockedAt;

  protected BillRun() {}

  public BillRun(String period, LocalDate billDate, LocalDate dueDate) {
    this.period = period;
    this.billDate = billDate;
    this.dueDate = dueDate;
  }

  public void totals(int billCount, long amountPaise, long gstPaise, String warningsJson) {
    this.billCount = billCount;
    this.amountPaise = amountPaise;
    this.gstPaise = gstPaise;
    this.totalPaise = Math.addExact(amountPaise, gstPaise);
    this.warnings = warningsJson;
  }

  public boolean isPreview() {
    return "PREVIEW".equals(status);
  }

  public void publish(Instant at, UUID by) {
    this.status = "PUBLISHED";
    this.publishedAt = at;
    this.publishedBy = by;
    this.lockedAt = at;
  }

  public void discard() {
    this.status = "DISCARDED";
  }

  public String getPeriod() { return period; }
  public String getStatus() { return status; }
  public LocalDate getBillDate() { return billDate; }
  public LocalDate getDueDate() { return dueDate; }
  public int getBillCount() { return billCount; }
  public long getAmountPaise() { return amountPaise; }
  public long getGstPaise() { return gstPaise; }
  public long getTotalPaise() { return totalPaise; }
  public String getWarnings() { return warnings; }
  public Instant getPublishedAt() { return publishedAt; }
  public UUID getPublishedBy() { return publishedBy; }
}
