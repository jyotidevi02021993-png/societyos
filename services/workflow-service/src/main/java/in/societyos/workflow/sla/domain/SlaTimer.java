package in.societyos.workflow.sla.domain;

import in.societyos.workflow.platform.core.UuidV7;
import in.societyos.workflow.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * An SLA clock on a subject (source of truth; db-scheduler only wakes the service at
 * {@link #nextWakeAt()}). ACTIVE → (warning at warn_at) → FIRED at due_at → escalation steps at
 * due_at + afterMins; STOPPED at any time when the subject is responded to / resolved.
 */
@Entity
@Table(name = "sla_timer")
public class SlaTimer extends TenantEntity {

  public enum Kind { RESPOND, RESOLVE }

  public enum Status { ACTIVE, STOPPED, FIRED }

  /** What a wake-up must announce. */
  public record Tick(boolean warn, boolean breach, List<Escalation> escalations) {
    public boolean isEmpty() {
      return !warn && !breach && escalations.isEmpty();
    }
  }

  public record Escalation(int level, String toRole) {}

  @Column(name = "subject_type", nullable = false, updatable = false)
  private String subjectType;
  @Column(name = "subject_id", nullable = false, updatable = false)
  private UUID subjectId;
  @Column(name = "subject_ref", updatable = false)
  private String subjectRef;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private Kind kind;
  @Column(name = "policy_id", updatable = false)
  private UUID policyId;
  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;
  @Column(name = "warn_at", updatable = false)
  private Instant warnAt;
  @Column(name = "due_at", nullable = false, updatable = false)
  private Instant dueAt;
  @Column(name = "warned_at")
  private Instant warnedAt;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;
  @Column(name = "fired_at")
  private Instant firedAt;
  @Column(name = "stopped_at")
  private Instant stoppedAt;
  @Column(nullable = false)
  private int level;
  @Column(name = "escalation_chain", nullable = false, updatable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String escalationChainJson;
  @Column(name = "next_escalation_at")
  private Instant nextEscalationAt;

  protected SlaTimer() {}

  public SlaTimer(String subjectType, UUID subjectId, String subjectRef, Kind kind, UUID policyId, Instant start,
      int minutes, int warnPercent, String escalationChainJson) {
    super(UuidV7.next());
    if (minutes <= 0) {
      throw new IllegalArgumentException("minutes must be positive");
    }
    this.subjectType = subjectType;
    this.subjectId = subjectId;
    this.subjectRef = subjectRef;
    this.kind = kind;
    this.policyId = policyId;
    this.startedAt = start;
    Duration total = Duration.ofMinutes(minutes);
    this.dueAt = start.plus(total);
    this.warnAt = start.plus(total.multipliedBy(warnPercent).dividedBy(100));
    this.status = Status.ACTIVE;
    this.escalationChainJson = escalationChainJson == null ? "[]" : escalationChainJson;
  }

  /**
   * Advances the clock to {@code now}. Pure: the caller publishes what the returned tick says and
   * schedules the next wake-up.
   */
  public Tick tick(Instant now, List<EscalationStep> chain) {
    boolean warn = false;
    boolean breach = false;
    List<Escalation> escalations = new ArrayList<>();
    if (status == Status.STOPPED) {
      return new Tick(false, false, List.of());
    }
    if (status == Status.ACTIVE) {
      if (!now.isBefore(dueAt)) {
        breach = true;
        status = Status.FIRED;
        firedAt = now;
        nextEscalationAt = chain.isEmpty() ? null : dueAt.plus(Duration.ofMinutes(chain.getFirst().afterMins()));
      } else if (warnAt != null && warnedAt == null && !now.isBefore(warnAt)) {
        warn = true;
        warnedAt = now;
      }
    }
    if (status == Status.FIRED) {
      while (nextEscalationAt != null && !now.isBefore(nextEscalationAt) && level < chain.size()) {
        EscalationStep step = chain.get(level);
        level++;
        escalations.add(new Escalation(level, step.toRole()));
        nextEscalationAt = level < chain.size()
            ? dueAt.plus(Duration.ofMinutes(chain.get(level).afterMins())) : null;
      }
    }
    return new Tick(warn, breach, List.copyOf(escalations));
  }

  /** When the next wake-up is needed, or null when nothing is left to do. */
  public Instant nextWakeAt() {
    return switch (status) {
      case STOPPED -> null;
      case ACTIVE -> warnAt != null && warnedAt == null && warnAt.isBefore(dueAt) ? warnAt : dueAt;
      case FIRED -> nextEscalationAt;
    };
  }

  /** Subject responded to / resolved. Returns false when already stopped. */
  public boolean stop(Instant now) {
    if (status == Status.STOPPED) {
      return false;
    }
    status = Status.STOPPED;
    stoppedAt = now;
    nextEscalationAt = null;
    return true;
  }

  public String getSubjectType() { return subjectType; }
  public UUID getSubjectId() { return subjectId; }
  public String getSubjectRef() { return subjectRef; }
  public Kind getKind() { return kind; }
  public UUID getPolicyId() { return policyId; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getWarnAt() { return warnAt; }
  public Instant getDueAt() { return dueAt; }
  public Instant getWarnedAt() { return warnedAt; }
  public Status getStatus() { return status; }
  public Instant getFiredAt() { return firedAt; }
  public Instant getStoppedAt() { return stoppedAt; }
  public int getLevel() { return level; }
  public String getEscalationChainJson() { return escalationChainJson; }
  public Instant getNextEscalationAt() { return nextEscalationAt; }
}
