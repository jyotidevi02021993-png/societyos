package in.societyos.workflow.sla.domain;

import in.societyos.workflow.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Every escalation, from an SLA breach chain or an undecided approval task. */
@Entity
@Table(name = "escalation_log")
public class EscalationLog extends TenantEntity {

  @Column(name = "subject_type", nullable = false, updatable = false)
  private String subjectType;
  @Column(name = "subject_id", nullable = false, updatable = false)
  private UUID subjectId;
  @Column(nullable = false, updatable = false)
  private int level;
  @Column(name = "to_role", nullable = false, updatable = false)
  private String toRole;
  @Column(nullable = false, updatable = false)
  private String reason;
  @Column(name = "ref_id", updatable = false)
  private UUID refId;
  @Column(nullable = false, updatable = false)
  private Instant at;

  protected EscalationLog() {}

  public EscalationLog(String subjectType, UUID subjectId, int level, String toRole, String reason, UUID refId,
      Instant at) {
    this.subjectType = subjectType;
    this.subjectId = subjectId;
    this.level = level;
    this.toRole = toRole;
    this.reason = reason;
    this.refId = refId;
    this.at = at;
  }

  public String getSubjectType() { return subjectType; }
  public UUID getSubjectId() { return subjectId; }
  public int getLevel() { return level; }
  public String getToRole() { return toRole; }
  public String getReason() { return reason; }
  public UUID getRefId() { return refId; }
  public Instant getAt() { return at; }
}
