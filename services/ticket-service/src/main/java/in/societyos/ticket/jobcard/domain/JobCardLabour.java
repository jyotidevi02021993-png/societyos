package in.societyos.ticket.jobcard.domain;

import in.societyos.ticket.platform.core.error.ProblemException;
import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One work-log line of a job card: who worked, how long, and the labour cost. */
@Entity
@Table(name = "job_card_labour")
public class JobCardLabour extends TenantEntity {

  @Column(name = "job_card_id", nullable = false, updatable = false)
  private UUID jobCardId;
  @Column(name = "worker_user_id", updatable = false)
  private UUID workerUserId;
  @Column(nullable = false, updatable = false, columnDefinition = "text")
  private String note;
  @Column(nullable = false, updatable = false)
  private int minutes;
  @Column(name = "cost_paise", nullable = false, updatable = false)
  private long costPaise;
  @Column(name = "logged_at", nullable = false, updatable = false)
  private Instant loggedAt;

  protected JobCardLabour() {}

  public JobCardLabour(UUID jobCardId, UUID workerUserId, String note, int minutes, long costPaise, Instant at) {
    if (note == null || note.isBlank()) {
      throw ProblemException.badRequest("NOTE_REQUIRED", "Describe the work done");
    }
    if (minutes < 0 || costPaise < 0) {
      throw ProblemException.badRequest("INVALID_WORK_LOG", "minutes and costPaise cannot be negative");
    }
    this.jobCardId = jobCardId;
    this.workerUserId = workerUserId;
    this.note = note.trim();
    this.minutes = minutes;
    this.costPaise = costPaise;
    this.loggedAt = at;
  }

  public UUID getJobCardId() { return jobCardId; }
  public UUID getWorkerUserId() { return workerUserId; }
  public String getNote() { return note; }
  public int getMinutes() { return minutes; }
  public long getCostPaise() { return costPaise; }
  public Instant getLoggedAt() { return loggedAt; }
}
