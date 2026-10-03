package in.societyos.ticket.jobcard.domain;

import in.societyos.ticket.platform.core.UuidV7;
import in.societyos.ticket.platform.core.error.ProblemException;
import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A unit of maintenance work, raised from a complaint, breakdown, PM task, failed checklist item
 * or incident. The rules of {@link JobCardStatus} are enforced here; once CLOSED the card is locked
 * ({@code locked_at}) and a database trigger rejects any further change.
 */
@Entity
@Table(name = "job_card")
public class JobCard extends TenantEntity {

  public static final Set<String> SOURCE_TYPES = Set.of("COMPLAINT", "BREAKDOWN", "PM", "CHECKLIST", "INCIDENT");
  public static final Set<String> WAITING_REASONS = Set.of("SPARE", "VENDOR", "OTHER");

  public enum Approval { NOT_REQUIRED, PENDING, APPROVED, REJECTED }

  @Column(nullable = false, updatable = false)
  private String number;
  @Column(name = "source_type", nullable = false, updatable = false)
  private String sourceType;
  @Column(name = "source_id", updatable = false)
  private UUID sourceId;
  @Column(name = "flat_id")
  private UUID flatId;
  @Column(name = "location_id")
  private UUID locationId;
  @Column(name = "asset_id")
  private UUID assetId;
  @Column(name = "category_name")
  private String categoryName;
  @Column(nullable = false)
  private String priority;
  @Column(nullable = false, columnDefinition = "text")
  private String fault;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private JobCardStatus status;
  @Column(name = "assignee_user_id")
  private UUID assigneeUserId;
  @Column(name = "vendor_id")
  private UUID vendorId;
  @Column(name = "assigned_at")
  private Instant assignedAt;
  @Column(name = "waiting_reason")
  private String waitingReason;
  @Column(name = "started_at")
  private Instant startedAt;
  @Column(name = "completed_at")
  private Instant completedAt;
  @Column(name = "work_done", columnDefinition = "text")
  private String workDone;
  @Column(name = "root_cause", columnDefinition = "text")
  private String rootCause;
  @Column(name = "verified_by")
  private UUID verifiedBy;
  @Column(name = "verified_at")
  private Instant verifiedAt;
  @Column(name = "closed_by")
  private UUID closedBy;
  @Column(name = "closed_at")
  private Instant closedAt;
  @Column(name = "labour_cost_paise", nullable = false)
  private long labourCostPaise;
  @Column(name = "spare_cost_paise", nullable = false)
  private long spareCostPaise;
  @Column(name = "recoverable_flat_id")
  private UUID recoverableFlatId;
  @Enumerated(EnumType.STRING)
  @Column(name = "approval_status", nullable = false)
  private Approval approvalStatus;
  @Column(name = "approval_instance_id")
  private UUID approvalInstanceId;
  @Column(name = "resident_confirmed")
  private Boolean residentConfirmed;
  @Column(name = "sla_breached", nullable = false)
  private boolean slaBreached;
  @Column(name = "escalation_level", nullable = false)
  private int escalationLevel;
  @Column(name = "escalated_to_role")
  private String escalatedToRole;
  @Column(name = "reopened_count", nullable = false)
  private int reopenedCount;
  @Column(name = "locked_at")
  private Instant lockedAt;

  protected JobCard() {}

  public JobCard(String number, String sourceType, UUID sourceId, UUID flatId, UUID locationId, UUID assetId,
      String categoryName, String priority, String fault) {
    super(UuidV7.next());
    if (!SOURCE_TYPES.contains(sourceType)) {
      throw ProblemException.badRequest("INVALID_SOURCE_TYPE", "sourceType must be one of " + SOURCE_TYPES);
    }
    if (fault == null || fault.isBlank()) {
      throw ProblemException.badRequest("FAULT_REQUIRED", "Describe the fault or work to be done");
    }
    this.number = number;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.flatId = flatId;
    this.locationId = locationId;
    this.assetId = assetId;
    this.categoryName = categoryName;
    this.priority = priority;
    this.fault = fault.trim();
    this.status = JobCardStatus.OPEN;
    this.approvalStatus = Approval.NOT_REQUIRED;
  }

  // --- state machine --------------------------------------------------------------------

  /** Assign or reassign to a technician and/or a vendor. Returns the previous status. */
  public JobCardStatus assign(UUID userId, UUID vendor, Instant now) {
    requireUnlocked();
    if (userId == null && vendor == null) {
      throw invalid("ASSIGNEE_REQUIRED", "An assignee or a vendor is required");
    }
    JobCardStatus previous = status;
    if (status == JobCardStatus.OPEN || status == JobCardStatus.REOPENED) {
      status = JobCardStatus.ASSIGNED;
    } else if (!status.isActiveWork()) {
      throw invalid("INVALID_TRANSITION", "A " + status + " job card cannot be reassigned");
    }
    this.assigneeUserId = userId;
    this.vendorId = vendor;
    this.assignedAt = now;
    return previous;
  }

  public JobCardStatus start(Instant now) {
    if (status == JobCardStatus.REOPENED && assigneeUserId == null && vendorId == null) {
      throw invalid("ASSIGNEE_REQUIRED", "Assign the reopened job card before starting it");
    }
    JobCardStatus previous = move(JobCardStatus.IN_PROGRESS);
    if (startedAt == null) {
      startedAt = now;
    }
    return previous;
  }

  public JobCardStatus waitFor(String reason) {
    if (reason == null || !WAITING_REASONS.contains(reason)) {
      throw ProblemException.badRequest("INVALID_WAITING_REASON", "reason must be one of " + WAITING_REASONS);
    }
    JobCardStatus previous = move(JobCardStatus.WAITING);
    waitingReason = reason;
    return previous;
  }

  public JobCardStatus resume() {
    if (status != JobCardStatus.WAITING) {
      throw invalid("INVALID_TRANSITION", "Only a waiting job card can be resumed");
    }
    waitingReason = null;
    return move(JobCardStatus.IN_PROGRESS);
  }

  /**
   * Work finished. Costs are the work-log labour and issued spares. A card costing more than
   * {@code approvalThresholdPaise} waits for a workflow approval before it can be verified.
   */
  public JobCardStatus complete(String work, String cause, long labourPaise, long sparePaise,
      UUID recoverableFlat, boolean hasAfterEvidence, long approvalThresholdPaise, Instant now) {
    if (status != JobCardStatus.IN_PROGRESS) {
      throw invalid("INVALID_TRANSITION", "Cannot move a job card from " + status + " to COMPLETED");
    }
    if (work == null || work.isBlank()) {
      throw invalid("WORK_DONE_REQUIRED", "Describe the work performed to complete the job card");
    }
    if (cause == null || cause.isBlank()) {
      throw invalid("ROOT_CAUSE_REQUIRED", "A root cause is required to complete the job card");
    }
    if (!hasAfterEvidence) {
      throw invalid("EVIDENCE_REQUIRED", "Add at least one AFTER photo before completing");
    }
    if (labourPaise < 0 || sparePaise < 0) {
      throw invalid("INVALID_COST", "Costs cannot be negative");
    }
    JobCardStatus previous = move(JobCardStatus.COMPLETED);
    workDone = work.trim();
    rootCause = cause.trim();
    labourCostPaise = labourPaise;
    spareCostPaise = sparePaise;
    recoverableFlatId = recoverableFlat;
    completedAt = now;
    approvalInstanceId = null;
    approvalStatus = totalCostPaise() > approvalThresholdPaise ? Approval.PENDING : Approval.NOT_REQUIRED;
    return previous;
  }

  /** The supervisor sends the work back. */
  public JobCardStatus rework() {
    if (status != JobCardStatus.COMPLETED) {
      throw invalid("INVALID_TRANSITION", "Only completed work can be sent back");
    }
    return backToWork();
  }

  public JobCardStatus verify(UUID by, Instant now) {
    if (status == JobCardStatus.COMPLETED && approvalStatus == Approval.PENDING) {
      throw invalid("APPROVAL_PENDING", "The cost of this job card is waiting for approval");
    }
    if (status == JobCardStatus.COMPLETED && approvalStatus == Approval.REJECTED) {
      throw invalid("APPROVAL_REJECTED", "The cost of this job card was rejected; rework it first");
    }
    JobCardStatus previous = move(JobCardStatus.VERIFIED);
    verifiedBy = by;
    verifiedAt = now;
    return previous;
  }

  /** The resident (or the manager for them) rejects the verified work. */
  public JobCardStatus reopen() {
    JobCardStatus previous = move(JobCardStatus.REOPENED);
    reopenedCount++;
    residentConfirmed = false;
    verifiedBy = null;
    verifiedAt = null;
    return previous;
  }

  /** Closes and locks the card; nothing about it can change afterwards. */
  public JobCardStatus close(UUID by, Boolean confirmedByResident, Instant now) {
    JobCardStatus previous = move(JobCardStatus.CLOSED);
    closedBy = by;
    closedAt = now;
    if (confirmedByResident != null) {
      residentConfirmed = confirmedByResident;
    }
    lockedAt = now;
    return previous;
  }

  // --- approvals (workflow-service) and SLA -------------------------------------------------

  public void approvalRequested(UUID instanceId) {
    requireUnlocked();
    if (status == JobCardStatus.COMPLETED && approvalStatus != Approval.APPROVED) {
      approvalStatus = Approval.PENDING;
      approvalInstanceId = instanceId;
    }
  }

  /** Returns true when the decision changed this card. Rejection sends the work back. */
  public boolean approvalDecided(UUID instanceId, boolean approved) {
    requireUnlocked();
    if (status != JobCardStatus.COMPLETED
        || approvalInstanceId != null && instanceId != null && !approvalInstanceId.equals(instanceId)) {
      return false;
    }
    approvalInstanceId = instanceId;
    if (approved) {
      approvalStatus = Approval.APPROVED;
    } else {
      approvalStatus = Approval.REJECTED;
      backToWork();
    }
    return true;
  }

  public void slaBreached() {
    requireUnlocked();
    slaBreached = true;
  }

  public void escalate(int level, String toRole) {
    requireUnlocked();
    if (level > escalationLevel) {
      escalationLevel = level;
      escalatedToRole = toRole;
    }
  }

  /** Spares issued after the card was completed still count towards its cost. */
  public void spareCostChanged(long sparePaise) {
    requireUnlocked();
    spareCostPaise = sparePaise;
  }

  public void requireUnlocked() {
    if (isLocked()) {
      throw ProblemException.conflict("JOB_CARD_LOCKED", "Job card " + number + " is closed and locked");
    }
  }

  public boolean isAssignedTo(UUID userId) {
    return userId != null && Objects.equals(userId, assigneeUserId);
  }

  public long totalCostPaise() {
    return labourCostPaise + spareCostPaise;
  }

  private JobCardStatus backToWork() {
    JobCardStatus previous = status;
    status = JobCardStatus.IN_PROGRESS;
    verifiedBy = null;
    verifiedAt = null;
    return previous;
  }

  private JobCardStatus move(JobCardStatus target) {
    requireUnlocked();
    if (!status.canMoveTo(target)) {
      throw invalid("INVALID_TRANSITION", "Cannot move a job card from " + status + " to " + target);
    }
    JobCardStatus previous = status;
    status = target;
    return previous;
  }

  private static ProblemException invalid(String code, String message) {
    return ProblemException.unprocessable(code, message);
  }

  public boolean isLocked() { return lockedAt != null; }
  public String getNumber() { return number; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public UUID getFlatId() { return flatId; }
  public UUID getLocationId() { return locationId; }
  public UUID getAssetId() { return assetId; }
  public String getCategoryName() { return categoryName; }
  public String getPriority() { return priority; }
  public String getFault() { return fault; }
  public JobCardStatus getStatus() { return status; }
  public UUID getAssigneeUserId() { return assigneeUserId; }
  public UUID getVendorId() { return vendorId; }
  public Instant getAssignedAt() { return assignedAt; }
  public String getWaitingReason() { return waitingReason; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public String getWorkDone() { return workDone; }
  public String getRootCause() { return rootCause; }
  public UUID getVerifiedBy() { return verifiedBy; }
  public Instant getVerifiedAt() { return verifiedAt; }
  public UUID getClosedBy() { return closedBy; }
  public Instant getClosedAt() { return closedAt; }
  public long getLabourCostPaise() { return labourCostPaise; }
  public long getSpareCostPaise() { return spareCostPaise; }
  public UUID getRecoverableFlatId() { return recoverableFlatId; }
  public Approval getApprovalStatus() { return approvalStatus; }
  public UUID getApprovalInstanceId() { return approvalInstanceId; }
  public Boolean getResidentConfirmed() { return residentConfirmed; }
  public boolean isSlaBreached() { return slaBreached; }
  public int getEscalationLevel() { return escalationLevel; }
  public String getEscalatedToRole() { return escalatedToRole; }
  public int getReopenedCount() { return reopenedCount; }
  public Instant getLockedAt() { return lockedAt; }
}
