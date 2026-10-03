package in.societyos.security.sos.domain;

import in.societyos.security.common.RuleViolation;
import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** An SOS: raised by a resident or guard, acknowledged by a responder, then resolved. */
@Entity
@Table(name = "sos")
public class SosAlert extends TenantEntity {

  public static final Set<String> KINDS = Set.of("MEDICAL", "FIRE", "SECURITY", "OTHER");

  @Column(name = "raised_by", nullable = false)
  private UUID raisedBy;

  @Column(name = "flat_id")
  private UUID flatId;

  @Column(nullable = false)
  private String kind;

  private String note;

  @Column(nullable = false)
  private Instant at;

  @Column(name = "acknowledged_by")
  private UUID acknowledgedBy;

  @Column(name = "acknowledged_at")
  private Instant acknowledgedAt;

  @Column(name = "resolved_by")
  private UUID resolvedBy;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  protected SosAlert() {}

  public SosAlert(UUID raisedBy, UUID flatId, String kind, String note, Instant at) {
    String k = kind == null ? "OTHER" : kind.trim().toUpperCase();
    if (!KINDS.contains(k)) {
      throw new RuleViolation("INVALID_SOS_KIND", "kind must be one of " + KINDS);
    }
    this.raisedBy = raisedBy;
    this.flatId = flatId;
    this.kind = k;
    this.note = note == null || note.isBlank() ? null : note.trim();
    this.at = at;
  }

  public void acknowledge(UUID by, Instant now) {
    if (resolvedAt != null) {
      throw new RuleViolation("SOS_RESOLVED", "This SOS is already resolved");
    }
    if (acknowledgedAt == null) {
      acknowledgedBy = by;
      acknowledgedAt = now;
    }
  }

  public void resolve(UUID by, Instant now) {
    if (resolvedAt != null) {
      throw new RuleViolation("SOS_RESOLVED", "This SOS is already resolved");
    }
    acknowledge(by, now);
    resolvedBy = by;
    resolvedAt = now;
  }

  public String state() {
    return resolvedAt != null ? "RESOLVED" : acknowledgedAt != null ? "ACKNOWLEDGED" : "OPEN";
  }

  public UUID getRaisedBy() {
    return raisedBy;
  }

  public UUID getFlatId() {
    return flatId;
  }

  public String getKind() {
    return kind;
  }

  public String getNote() {
    return note;
  }

  public Instant getAt() {
    return at;
  }

  public UUID getAcknowledgedBy() {
    return acknowledgedBy;
  }

  public Instant getAcknowledgedAt() {
    return acknowledgedAt;
  }

  public UUID getResolvedBy() {
    return resolvedBy;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }
}
