package in.societyos.asset.pm.domain;

import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** One occurrence of a PM plan: DUE → IN_PROGRESS → DONE (or OVERDUE / MISSED / CANCELLED). */
@Entity
@Table(name = "pm_task")
public class PmTask extends TenantEntity {

  public enum Status { DUE, IN_PROGRESS, OVERDUE, DONE, MISSED, CANCELLED }

  public enum Trigger { CALENDAR, USAGE, MANUAL }

  public static final Set<Status> OPEN = EnumSet.of(Status.DUE, Status.IN_PROGRESS, Status.OVERDUE);

  @Column(nullable = false, updatable = false)
  private String number;

  @Column(name = "pm_plan_id", nullable = false, updatable = false)
  private UUID pmPlanId;

  @Column(name = "asset_id", nullable = false, updatable = false)
  private UUID assetId;

  @Column(name = "due_on", nullable = false)
  private LocalDate dueOn;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_kind", nullable = false, updatable = false)
  private Trigger trigger;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Column(name = "job_card_id")
  private UUID jobCardId;

  @Column(name = "assignee_user_id")
  private UUID assigneeUserId;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "started_by")
  private UUID startedBy;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "completed_by")
  private UUID completedBy;

  @Column(name = "results", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String resultsJson;

  @Column(name = "ok_count", nullable = false)
  private int okCount;

  @Column(name = "failed_count", nullable = false)
  private int failedCount;

  private String remarks;

  @Column(name = "photo_media_ids", nullable = false, columnDefinition = "uuid[]")
  private UUID[] photoMediaIds;

  @Column(name = "cost_paise", nullable = false)
  private long costPaise;

  @Column(name = "usage_at_done")
  private BigDecimal usageAtDone;

  @Column(name = "overdue_notified", nullable = false)
  private boolean overdueNotified;

  protected PmTask() {}

  public PmTask(String number, PmPlan plan, LocalDate dueOn, Trigger trigger) {
    super(UuidV7.next());
    this.number = number;
    this.pmPlanId = plan.getId();
    this.assetId = plan.getAssetId();
    this.dueOn = dueOn;
    this.trigger = trigger;
    this.status = Status.DUE;
    this.assigneeUserId = plan.getAssigneeUserId();
    this.resultsJson = "[]";
    this.photoMediaIds = new UUID[0];
  }

  public boolean isOpen() {
    return OPEN.contains(status);
  }

  public void start(UUID userId, Instant at) {
    requireOpen();
    if (status != Status.IN_PROGRESS) {
      status = Status.IN_PROGRESS;
      startedAt = at;
      startedBy = userId;
    }
  }

  /** Records the checklist outcome and closes the task. */
  public void complete(
      UUID userId, Instant at, String resultsJson, int ok, int failed, String remarks,
      UUID[] photos, long costPaise, BigDecimal usageAtDone) {
    requireOpen();
    if (costPaise < 0) {
      throw new IllegalArgumentException("costPaise cannot be negative");
    }
    if (startedAt == null) {
      startedAt = at;
      startedBy = userId;
    }
    this.status = Status.DONE;
    this.completedAt = at;
    this.completedBy = userId;
    this.resultsJson = resultsJson == null ? "[]" : resultsJson;
    this.okCount = ok;
    this.failedCount = failed;
    this.remarks = remarks;
    this.photoMediaIds = photos == null ? new UUID[0] : photos;
    this.costPaise = costPaise;
    this.usageAtDone = usageAtDone;
  }

  /** Closed through the job card that ticket-service ran for it. */
  public void completeFromJobCard(UUID jobCardId, Instant at, long costPaise) {
    if (!isOpen()) {
      return;
    }
    this.jobCardId = jobCardId;
    this.status = Status.DONE;
    this.completedAt = at;
    this.costPaise = Math.max(0, costPaise);
  }

  public void linkJobCard(UUID jobCardId) {
    this.jobCardId = jobCardId;
  }

  /** Marks the task overdue; returns true the first time, so the alert is sent once. */
  public boolean markOverdue() {
    if (!isOpen() || overdueNotified) {
      return false;
    }
    if (status == Status.DUE) {
      status = Status.OVERDUE;
    }
    overdueNotified = true;
    return true;
  }

  /** A newer occurrence replaced this one before it was done. */
  public void miss() {
    if (isOpen()) {
      status = Status.MISSED;
    }
  }

  public void cancel() {
    requireOpen();
    status = Status.CANCELLED;
  }

  public void assign(UUID userId) {
    requireOpen();
    this.assigneeUserId = userId;
  }

  private void requireOpen() {
    if (!isOpen()) {
      throw new IllegalStateException("PM task " + number + " is " + status);
    }
  }

  public String getNumber() { return number; }
  public UUID getPmPlanId() { return pmPlanId; }
  public UUID getAssetId() { return assetId; }
  public LocalDate getDueOn() { return dueOn; }
  public Trigger getTrigger() { return trigger; }
  public Status getStatus() { return status; }
  public UUID getJobCardId() { return jobCardId; }
  public UUID getAssigneeUserId() { return assigneeUserId; }
  public Instant getStartedAt() { return startedAt; }
  public UUID getStartedBy() { return startedBy; }
  public Instant getCompletedAt() { return completedAt; }
  public UUID getCompletedBy() { return completedBy; }
  public String getResultsJson() { return resultsJson; }
  public int getOkCount() { return okCount; }
  public int getFailedCount() { return failedCount; }
  public String getRemarks() { return remarks; }
  public UUID[] getPhotoMediaIds() { return photoMediaIds == null ? new UUID[0] : photoMediaIds.clone(); }
  public long getCostPaise() { return costPaise; }
  public BigDecimal getUsageAtDone() { return usageAtDone; }
}
