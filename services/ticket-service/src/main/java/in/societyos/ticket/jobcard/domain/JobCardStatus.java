package in.societyos.ticket.jobcard.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Job card state machine (docs/architecture/01 §6, Req §11):
 *
 * <pre>
 * OPEN → ASSIGNED → IN_PROGRESS ⇄ WAITING (spare | vendor) → COMPLETED → VERIFIED → CLOSED
 *                      ↑              ↑ (rework / approval rejected) ┘          │
 *                      └──────────── REOPENED ◄─────────────────────────────────┘ (resident rejects)
 * </pre>
 */
public enum JobCardStatus {
  OPEN, ASSIGNED, IN_PROGRESS, WAITING, COMPLETED, VERIFIED, CLOSED, REOPENED;

  public Set<JobCardStatus> next() {
    return switch (this) {
      case OPEN -> EnumSet.of(ASSIGNED);
      case ASSIGNED -> EnumSet.of(IN_PROGRESS);
      case IN_PROGRESS -> EnumSet.of(WAITING, COMPLETED);
      case WAITING -> EnumSet.of(IN_PROGRESS);
      case COMPLETED -> EnumSet.of(VERIFIED, IN_PROGRESS);
      case VERIFIED -> EnumSet.of(CLOSED, REOPENED);
      case REOPENED -> EnumSet.of(ASSIGNED, IN_PROGRESS);
      case CLOSED -> EnumSet.noneOf(JobCardStatus.class);
    };
  }

  public boolean canMoveTo(JobCardStatus target) {
    return next().contains(target);
  }

  /** Statuses in which the technician (or vendor) is expected to be working. */
  public boolean isActiveWork() {
    return this == ASSIGNED || this == IN_PROGRESS || this == WAITING || this == REOPENED;
  }
}
