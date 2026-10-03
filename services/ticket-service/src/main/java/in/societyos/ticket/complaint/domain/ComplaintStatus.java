package in.societyos.ticket.complaint.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Complaint state machine:
 *
 * <pre>
 * OPEN → IN_PROGRESS (job card raised) → RESOLVED (job card verified / manager) → CLOSED (resident accepts)
 *   │                                        │  ▲                                    │
 *   ├→ CANCELLED (resident withdraws)        ▼  │                                    │
 *   └→ REJECTED (manager: invalid)        REOPENED ◄─────────── resident rejects / reopens within window
 * </pre>
 */
public enum ComplaintStatus {
  OPEN, IN_PROGRESS, RESOLVED, CLOSED, REOPENED, CANCELLED, REJECTED;

  public Set<ComplaintStatus> next() {
    return switch (this) {
      case OPEN -> EnumSet.of(IN_PROGRESS, RESOLVED, CANCELLED, REJECTED);
      case IN_PROGRESS -> EnumSet.of(RESOLVED);
      case RESOLVED -> EnumSet.of(CLOSED, REOPENED);
      case CLOSED -> EnumSet.of(REOPENED);
      case REOPENED -> EnumSet.of(IN_PROGRESS, RESOLVED, REJECTED);
      case CANCELLED, REJECTED -> EnumSet.noneOf(ComplaintStatus.class);
    };
  }

  public boolean canMoveTo(ComplaintStatus target) {
    return next().contains(target);
  }

  public boolean isFinal() {
    return this == CANCELLED || this == REJECTED;
  }
}
