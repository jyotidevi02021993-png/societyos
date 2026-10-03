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
import java.util.UUID;

/** One run of an approval workflow for a subject (a PO, a job card's cost, an expense, …). */
@Entity
@Table(name = "workflow_instance")
public class WorkflowInstance extends TenantEntity {

  public enum Status { RUNNING, APPROVED, REJECTED, CANCELLED }

  @Column(name = "definition_id", updatable = false)
  private UUID definitionId;
  @Column(name = "definition_version", updatable = false)
  private Integer definitionVersion;
  @Column(name = "subject_type", nullable = false, updatable = false)
  private String subjectType;
  @Column(name = "subject_id", nullable = false, updatable = false)
  private UUID subjectId;
  @Column(name = "subject_ref", updatable = false)
  private String subjectRef;
  @Column(name = "amount_paise", nullable = false, updatable = false)
  private long amountPaise;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;
  @Column(name = "current_step", nullable = false)
  private int currentStep;
  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;
  @Column(name = "ended_at")
  private Instant endedAt;
  @Column(name = "decided_by")
  private UUID decidedBy;
  @Column(columnDefinition = "text")
  private String comment;

  protected WorkflowInstance() {}

  public WorkflowInstance(UUID definitionId, Integer definitionVersion, String subjectType, UUID subjectId,
      String subjectRef, long amountPaise, Instant now) {
    super(UuidV7.next());
    if (amountPaise < 0) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "amountPaise cannot be negative");
    }
    this.definitionId = definitionId;
    this.definitionVersion = definitionVersion;
    this.subjectType = subjectType;
    this.subjectId = subjectId;
    this.subjectRef = subjectRef;
    this.amountPaise = amountPaise;
    this.status = Status.RUNNING;
    this.startedAt = now;
  }

  /** Moves to step {@code step} (1-based). */
  public void atStep(int step) {
    requireRunning();
    this.currentStep = step;
  }

  public void approve(UUID by, String note, Instant now) {
    end(Status.APPROVED, by, note, now);
  }

  public void reject(UUID by, String note, Instant now) {
    end(Status.REJECTED, by, note, now);
  }

  public void cancel(UUID by, String note, Instant now) {
    end(Status.CANCELLED, by, note, now);
  }

  public boolean isRunning() {
    return status == Status.RUNNING;
  }

  private void end(Status to, UUID by, String note, Instant now) {
    requireRunning();
    status = to;
    decidedBy = by;
    comment = note;
    endedAt = now;
  }

  private void requireRunning() {
    if (status != Status.RUNNING) {
      throw ProblemException.conflict("INSTANCE_ENDED", "This workflow is already " + status);
    }
  }

  public UUID getDefinitionId() { return definitionId; }
  public Integer getDefinitionVersion() { return definitionVersion; }
  public String getSubjectType() { return subjectType; }
  public UUID getSubjectId() { return subjectId; }
  public String getSubjectRef() { return subjectRef; }
  public long getAmountPaise() { return amountPaise; }
  public Status getStatus() { return status; }
  public int getCurrentStep() { return currentStep; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getEndedAt() { return endedAt; }
  public UUID getDecidedBy() { return decidedBy; }
  public String getComment() { return comment; }
}
