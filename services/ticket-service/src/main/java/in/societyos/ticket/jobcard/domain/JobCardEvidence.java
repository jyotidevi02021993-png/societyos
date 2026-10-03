package in.societyos.ticket.jobcard.domain;

import in.societyos.ticket.platform.core.error.ProblemException;
import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A photo (media-service id) taken before, during or after the work, with where and when. */
@Entity
@Table(name = "job_card_evidence")
public class JobCardEvidence extends TenantEntity {

  public static final Set<String> STAGES = Set.of("BEFORE", "DURING", "AFTER");

  @Column(name = "job_card_id", nullable = false, updatable = false)
  private UUID jobCardId;
  @Column(nullable = false, updatable = false)
  private String stage;
  @Column(name = "media_id", nullable = false, updatable = false)
  private UUID mediaId;
  @Column(name = "taken_at", nullable = false, updatable = false)
  private Instant takenAt;
  @Column(updatable = false)
  private Double lat;
  @Column(updatable = false)
  private Double lng;
  @Column(name = "taken_by", updatable = false)
  private UUID takenBy;

  protected JobCardEvidence() {}

  public JobCardEvidence(UUID jobCardId, String stage, UUID mediaId, Instant takenAt, Double lat, Double lng,
      UUID takenBy) {
    if (!STAGES.contains(stage)) {
      throw ProblemException.badRequest("INVALID_STAGE", "stage must be one of " + STAGES);
    }
    if (mediaId == null) {
      throw ProblemException.badRequest("MEDIA_REQUIRED", "mediaId is required");
    }
    this.jobCardId = jobCardId;
    this.stage = stage;
    this.mediaId = mediaId;
    this.takenAt = takenAt;
    this.lat = lat;
    this.lng = lng;
    this.takenBy = takenBy;
  }

  public UUID getJobCardId() { return jobCardId; }
  public String getStage() { return stage; }
  public UUID getMediaId() { return mediaId; }
  public Instant getTakenAt() { return takenAt; }
  public Double getLat() { return lat; }
  public Double getLng() { return lng; }
  public UUID getTakenBy() { return takenBy; }
}
