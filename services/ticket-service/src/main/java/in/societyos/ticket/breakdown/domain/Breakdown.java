package in.societyos.ticket.breakdown.domain;

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

/**
 * An equipment or common-area failure (P1..P4). Always gets a job card straight away;
 * REPORTED → IN_REPAIR (work started) → RESOLVED (job card closed, downtime recorded).
 */
@Entity
@Table(name = "breakdown")
public class Breakdown extends TenantEntity {

  public enum Status { REPORTED, IN_REPAIR, RESOLVED }

  @Column(nullable = false, updatable = false)
  private String number;
  @Column(name = "asset_id")
  private UUID assetId;
  @Column(name = "location_id")
  private UUID locationId;
  @Column(name = "reported_by", updatable = false)
  private UUID reportedBy;
  @Column(nullable = false, columnDefinition = "text")
  private String fault;
  @Column(nullable = false)
  private String priority;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;
  @Column(nullable = false, updatable = false)
  private String source;
  @Column(name = "source_ref", updatable = false)
  private String sourceRef;
  @Column(name = "job_card_id")
  private UUID jobCardId;
  @Column(name = "reported_at", nullable = false, updatable = false)
  private Instant reportedAt;
  @Column(name = "expected_resolution_at")
  private Instant expectedResolutionAt;
  @Column(name = "resolved_at")
  private Instant resolvedAt;
  @Column(name = "downtime_mins")
  private Integer downtimeMins;
  @Column(name = "sla_breached", nullable = false)
  private boolean slaBreached;
  @Column(name = "escalation_level", nullable = false)
  private int escalationLevel;
  @Column(name = "escalated_to_role")
  private String escalatedToRole;

  protected Breakdown() {}

  public Breakdown(String number, UUID assetId, UUID locationId, UUID reportedBy, String fault, String priority,
      String source, String sourceRef, Instant reportedAt, Instant expectedResolutionAt) {
    super(UuidV7.next());
    if (fault == null || fault.isBlank()) {
      throw ProblemException.badRequest("FAULT_REQUIRED", "Describe the fault");
    }
    if (assetId == null && locationId == null) {
      throw ProblemException.badRequest("WHERE_REQUIRED", "Give the asset or the location");
    }
    this.number = number;
    this.assetId = assetId;
    this.locationId = locationId;
    this.reportedBy = reportedBy;
    this.fault = fault.trim();
    this.priority = priority;
    this.source = source;
    this.sourceRef = sourceRef;
    this.reportedAt = reportedAt;
    this.expectedResolutionAt = expectedResolutionAt;
    this.status = Status.REPORTED;
  }

  public void jobCardRaised(UUID cardId) {
    this.jobCardId = cardId;
  }

  public Status repairStarted() {
    Status previous = status;
    if (status == Status.REPORTED) {
      status = Status.IN_REPAIR;
    }
    return previous;
  }

  public Status resolved(Instant at) {
    Status previous = status;
    if (status != Status.RESOLVED) {
      status = Status.RESOLVED;
      resolvedAt = at;
      downtimeMins = (int) Math.max(0, Duration.between(reportedAt, at).toMinutes());
    }
    return previous;
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

  public String getNumber() { return number; }
  public UUID getAssetId() { return assetId; }
  public UUID getLocationId() { return locationId; }
  public UUID getReportedBy() { return reportedBy; }
  public String getFault() { return fault; }
  public String getPriority() { return priority; }
  public Status getStatus() { return status; }
  public String getSource() { return source; }
  public String getSourceRef() { return sourceRef; }
  public UUID getJobCardId() { return jobCardId; }
  public Instant getReportedAt() { return reportedAt; }
  public Instant getExpectedResolutionAt() { return expectedResolutionAt; }
  public Instant getResolvedAt() { return resolvedAt; }
  public Integer getDowntimeMins() { return downtimeMins; }
  public boolean isSlaBreached() { return slaBreached; }
  public int getEscalationLevel() { return escalationLevel; }
  public String getEscalatedToRole() { return escalatedToRole; }
}
