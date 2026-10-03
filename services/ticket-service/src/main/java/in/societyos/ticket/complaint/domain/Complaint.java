package in.societyos.ticket.complaint.domain;

import in.societyos.ticket.platform.core.UuidV7;
import in.societyos.ticket.platform.core.error.ProblemException;
import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** A resident's complaint. Work happens on its job card; the resident confirms and rates at the end. */
@Entity
@Table(name = "complaint")
public class Complaint extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String number;
  @Column(name = "flat_id")
  private UUID flatId;
  @Column(name = "location_id")
  private UUID locationId;
  @Column(name = "asset_id")
  private UUID assetId;
  @Column(name = "raised_by", updatable = false)
  private UUID raisedBy;
  @Column(name = "category_id")
  private UUID categoryId;
  @Column(name = "category_name")
  private String categoryName;
  @Column(nullable = false)
  private String priority;
  @Column(nullable = false, columnDefinition = "text")
  private String description;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ComplaintStatus status;
  @Column(name = "job_card_id")
  private UUID jobCardId;
  @Column(name = "sla_due_at")
  private Instant slaDueAt;
  @Column(name = "sla_breached", nullable = false)
  private boolean slaBreached;
  @Column(name = "escalation_level", nullable = false)
  private int escalationLevel;
  @Column(name = "escalated_to_role")
  private String escalatedToRole;
  @Column(name = "resolved_at")
  private Instant resolvedAt;
  @Column(name = "closed_at")
  private Instant closedAt;
  private Short rating;
  @Column(columnDefinition = "text")
  private String feedback;
  @Column(name = "reopened_count", nullable = false)
  private int reopenedCount;

  protected Complaint() {}

  public Complaint(String number, UUID flatId, UUID locationId, UUID assetId, UUID raisedBy, UUID categoryId,
      String categoryName, String priority, String description, Instant slaDueAt) {
    super(UuidV7.next());
    if (description == null || description.isBlank()) {
      throw ProblemException.badRequest("TEXT_REQUIRED", "Describe the problem");
    }
    if (flatId == null && locationId == null && assetId == null) {
      throw ProblemException.badRequest("WHERE_REQUIRED", "Give a flat, a location or an asset");
    }
    this.number = number;
    this.flatId = flatId;
    this.locationId = locationId;
    this.assetId = assetId;
    this.raisedBy = raisedBy;
    this.categoryId = categoryId;
    this.categoryName = categoryName;
    this.priority = priority;
    this.description = description.trim();
    this.slaDueAt = slaDueAt;
    this.status = ComplaintStatus.OPEN;
  }

  /** A job card now carries the work. Returns the previous status. */
  public ComplaintStatus jobCardRaised(UUID cardId) {
    ComplaintStatus previous = move(ComplaintStatus.IN_PROGRESS);
    this.jobCardId = cardId;
    return previous;
  }

  public ComplaintStatus resolve(Instant now) {
    ComplaintStatus previous = move(ComplaintStatus.RESOLVED);
    resolvedAt = now;
    return previous;
  }

  /** Closed without resident feedback (the manager closed the job card). */
  public ComplaintStatus close(Instant now) {
    ComplaintStatus previous = move(ComplaintStatus.CLOSED);
    closedAt = now;
    return previous;
  }

  /** The resident accepts (closes, with a rating) or rejects (reopens) the resolution. */
  public ComplaintStatus feedback(boolean accepted, Integer stars, String comment, Instant now) {
    if (status != ComplaintStatus.RESOLVED) {
      throw invalid("NOT_RESOLVED", "Feedback is taken once the complaint is resolved");
    }
    if (stars != null && (stars < 1 || stars > 5)) {
      throw ProblemException.badRequest("INVALID_RATING", "rating must be 1 to 5");
    }
    if (accepted && stars == null) {
      throw ProblemException.badRequest("RATING_REQUIRED", "Rate the work from 1 to 5");
    }
    rating = stars == null ? null : stars.shortValue();
    feedback = comment == null || comment.isBlank() ? null : comment.trim();
    if (accepted) {
      return close(now);
    }
    return reopenNow();
  }

  /** The resident reopens a closed complaint within {@code window} of closing. */
  public ComplaintStatus reopen(Instant now, Duration window) {
    if (status == ComplaintStatus.CLOSED && closedAt != null && closedAt.plus(window).isBefore(now)) {
      throw invalid("REOPEN_WINDOW_OVER", "A complaint can be reopened within " + window.toDays() + " days");
    }
    return reopenNow();
  }

  /** Detaches a closed (locked) job card so a new one can be raised. */
  public void detachJobCard() {
    jobCardId = null;
  }

  public ComplaintStatus cancel() {
    return move(ComplaintStatus.CANCELLED);
  }

  public ComplaintStatus reject(String reason) {
    if (reason == null || reason.isBlank()) {
      throw ProblemException.badRequest("REASON_REQUIRED", "Give a reason for rejecting the complaint");
    }
    return move(ComplaintStatus.REJECTED);
  }

  public void slaBreached() {
    slaBreached = true;
  }

  public void escalate(int level, String toRole) {
    if (level > escalationLevel) {
      escalationLevel = level;
      escalatedToRole = toRole;
    }
  }

  public boolean isOpenForWork() {
    return status == ComplaintStatus.OPEN || status == ComplaintStatus.REOPENED;
  }

  private ComplaintStatus reopenNow() {
    ComplaintStatus previous = move(ComplaintStatus.REOPENED);
    reopenedCount++;
    resolvedAt = null;
    closedAt = null;
    return previous;
  }

  private ComplaintStatus move(ComplaintStatus target) {
    if (!status.canMoveTo(target)) {
      throw invalid("INVALID_TRANSITION", "Cannot move a complaint from " + status + " to " + target);
    }
    ComplaintStatus previous = status;
    status = target;
    return previous;
  }

  private static ProblemException invalid(String code, String message) {
    return ProblemException.unprocessable(code, message);
  }

  public String getNumber() { return number; }
  public UUID getFlatId() { return flatId; }
  public UUID getLocationId() { return locationId; }
  public UUID getAssetId() { return assetId; }
  public UUID getRaisedBy() { return raisedBy; }
  public UUID getCategoryId() { return categoryId; }
  public String getCategoryName() { return categoryName; }
  public String getPriority() { return priority; }
  public String getDescription() { return description; }
  public ComplaintStatus getStatus() { return status; }
  public UUID getJobCardId() { return jobCardId; }
  public Instant getSlaDueAt() { return slaDueAt; }
  public boolean isSlaBreached() { return slaBreached; }
  public int getEscalationLevel() { return escalationLevel; }
  public String getEscalatedToRole() { return escalatedToRole; }
  public Instant getResolvedAt() { return resolvedAt; }
  public Instant getClosedAt() { return closedAt; }
  public Integer getRating() { return rating == null ? null : rating.intValue(); }
  public String getFeedback() { return feedback; }
  public int getReopenedCount() { return reopenedCount; }
}
