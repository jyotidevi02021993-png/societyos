package in.societyos.security.incident.domain;

import in.societyos.security.common.RuleViolation;
import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An incident seen at the gate or on rounds (trespass, fight, theft, suspicious vehicle...). The
 * follow-up work (RCA, job cards) lives in ticket-service, which listens to the event.
 */
@Entity
@Table(name = "gate_incident")
public class GateIncident extends TenantEntity {

  public static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

  @Column(nullable = false)
  private String kind;

  @Column(nullable = false)
  private String severity;

  @Column(name = "location_text")
  private String locationText;

  private String description;

  @Column(name = "photo_media_id")
  private UUID photoMediaId;

  @Column(name = "gate_id")
  private UUID gateId;

  @Column(name = "reported_by", nullable = false)
  private UUID reportedBy;

  @Column(nullable = false)
  private Instant at;

  protected GateIncident() {}

  public GateIncident(String kind, String severity, String locationText, String description, UUID photoMediaId,
      UUID gateId, UUID reportedBy, Instant at) {
    if (kind == null || kind.isBlank()) {
      throw new RuleViolation("INCIDENT_KIND_REQUIRED", "kind is required");
    }
    String sev = severity == null ? "MEDIUM" : severity.trim().toUpperCase();
    if (!SEVERITIES.contains(sev)) {
      throw new RuleViolation("INVALID_SEVERITY", "severity must be one of " + SEVERITIES);
    }
    this.kind = kind.trim().toUpperCase();
    this.severity = sev;
    this.locationText = locationText;
    this.description = description;
    this.photoMediaId = photoMediaId;
    this.gateId = gateId;
    this.reportedBy = reportedBy;
    this.at = at;
  }

  /** HIGH and CRITICAL incidents page guards and managers immediately. */
  public boolean isSerious() {
    return SEVERITIES.indexOf(severity) >= SEVERITIES.indexOf("HIGH");
  }

  public String getKind() {
    return kind;
  }

  public String getSeverity() {
    return severity;
  }

  public String getLocationText() {
    return locationText;
  }

  public String getDescription() {
    return description;
  }

  public UUID getPhotoMediaId() {
    return photoMediaId;
  }

  public UUID getGateId() {
    return gateId;
  }

  public UUID getReportedBy() {
    return reportedBy;
  }

  public Instant getAt() {
    return at;
  }
}
