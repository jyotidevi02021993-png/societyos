package in.societyos.workflow.approval.domain;

import in.societyos.workflow.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One approver's vote on a task (unique per task and user). */
@Entity
@Table(name = "approval_decision")
public class ApprovalDecision extends TenantEntity {

  @Column(name = "task_id", nullable = false, updatable = false)
  private UUID taskId;
  @Column(name = "decided_by", nullable = false, updatable = false)
  private UUID decidedBy;
  @Column(nullable = false, updatable = false)
  private String decision;
  @Column(updatable = false, columnDefinition = "text")
  private String comment;
  @Column(name = "decided_at", nullable = false, updatable = false)
  private Instant decidedAt;

  protected ApprovalDecision() {}

  public ApprovalDecision(UUID taskId, UUID decidedBy, String decision, String comment, Instant decidedAt) {
    this.taskId = taskId;
    this.decidedBy = decidedBy;
    this.decision = decision;
    this.comment = comment;
    this.decidedAt = decidedAt;
  }

  public UUID getTaskId() { return taskId; }
  public UUID getDecidedBy() { return decidedBy; }
  public String getDecision() { return decision; }
  public String getComment() { return comment; }
  public Instant getDecidedAt() { return decidedAt; }
}
