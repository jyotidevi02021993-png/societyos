package in.societyos.workflow.scheduling.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable wake-ups (ADR-0004). The row identified by {@code id} (an SLA timer, an approval task) is
 * the source of truth; a wake-up only asks its {@link TimerHandler} to look at it again, so an
 * extra or late wake-up is harmless. Scheduled after the current transaction commits.
 */
public interface Timers {

  void wakeAt(String taskName, UUID societyId, UUID id, Instant at);

  /** Handles wake-ups for one task name; runs as the system actor of the row's society. */
  interface TimerHandler {
    String taskName();

    void wake(UUID id);
  }
}
