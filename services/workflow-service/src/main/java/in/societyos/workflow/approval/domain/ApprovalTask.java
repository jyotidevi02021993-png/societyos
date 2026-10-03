package in.societyos.workflow.approval.domain;

import in.societyos.workflow.platform.core.UuidV7;
import in.societyos.workflow.platform.core.error.ProblemException;
import in.societyos.workflow.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One step of an approval: decided by a named user or by holders of a role, possibly needing
 * several approvals ("2 of 5 committee members"). Escalates to another role when undecided by {@code dueAt}.
 */
@Entity
@Table(name = "approval_task")
public class ApprovalTask extends TenantEntity {

  public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

  @Column(name = "instance_id", nullable = false, updatable = false)
  private UUID instanceId;
  @Column(nullable = false, updatable = false)
  private int step;
  @Column(name = "step_name", nullable = false, updatable = false)
  private String stepName;
  @Column(name = "approver_role")
  private String approverRole;
  @Column(name = "approver_user_id")
  private UUID approverUserId;
  @Column(name = "required_approvals", nullable = false, updatable = false)
  private int requiredApprovals;
  @Column(name = "approvals_count", nullable = false)
  private int approvalsCount;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;
  @Column(name = "due_at")
  private Instant dueAt;
  @Column(name = "escalate_to_role")
  private String escalateToRole;
  @Column(name = "escalation_level", nullable = false)
  private int escalationLevel;
  @Column(name = "decided_by")
  private UUID decidedBy;
  @Column(name = "decided_at")
  private Instant decidedAt;
  @Column(columnDefinition = "text")
  private String comment;

  protected ApprovalTask() {}

  public ApprovalTask(UUID instanceId, int step, String stepName, String approverRole, UUID approverUserId,
      int requiredApprovals, Instant dueAt, String escalateToRole) {
    super(UuidV7.next());
    this.instanceId = instanceId;
    this.step = step;
    this.stepName = stepName;
    this.approverRole = approverRole;
    this.approverUserId = approverUserId;
    this.requiredApprovals = Math.max(1, requiredApprovals);
    this.dueAt = dueAt;
    this.escalateToRole = escalateToRole;
    this.status = Status.PENDING;
  }

  public boolean canBeDecidedBy(UUID userId, Set<String> roles) {
    if (userId == null) {
      return false;
    }
    return userId.equals(approverUserId) || approverRole != null && roles.contains(approverRole);
  }

  /** Records one approval. Returns true when the step is now fully approved. */
  public boolean approve(UUID by, String note, Instant now) {
    requirePending();
    approvalsCount++;
    if (approvalsCount >= requiredApprovals) {
      status = Status.APPROVED;
      decidedBy = by;
      decidedAt = now;
      comment = note;
      return true;
    }
    return false;
  }

  /** One rejection rejects the step (and the workflow). */
  public void reject(UUID by, String note, Instant now) {
    requirePending();
    status = Status.REJECTED;
    decidedBy = by;
    decidedAt = now;
    comment = note;
  }

  public void cancel() {
    if (status == Status.PENDING) {
      status = Status.CANCELLED;
    }
  }

  /** Undecided past its due time: hand the task to the escalation role. Returns true if it escalated. */
  public boolean escalateIfDue(Instant now) {
    if (status != Status.PENDING || dueAt == null || now.isBefore(dueAt) || escalateToRole == null) {
      return false;
    }
    approverRole = escalateToRole;
    approverUserId = null;
    escalateToRole = null;
    dueAt = null;
    escalationLevel++;
    return true;
  }

  public boolean isPending() {
    return status == Status.PENDING;
  }

  private void requirePending() {
    if (status != Status.PENDING) {
      throw ProblemException.conflict("TASK_DECIDED", "This approval task is already " + status);
    }
  }

  public UUID getInstanceId() { return instanceId; }
  public int getStep() { return step; }
  public String getStepName() { return stepName; }
  public String getApproverRole() { return approverRole; }
  public UUID getApproverUserId() { return approverUserId; }
  public int getRequiredApprovals() { return requiredApprovals; }
  public int getApprovalsCount() { return approvalsCount; }
  public Status getStatus() { return status; }
  public Instant getDueAt() { return dueAt; }
  public String getEscalateToRole() { return escalateToRole; }
  public int getEscalationLevel() { return escalationLevel; }
  public UUID getDecidedBy() { return decidedBy; }
  public Instant getDecidedAt() { return decidedAt; }
  public String getComment() { return comment; }
}
