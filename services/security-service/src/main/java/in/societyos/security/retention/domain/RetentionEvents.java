package in.societyos.security.retention.domain;

import in.societyos.security.common.SecurityEvent;
import java.time.Instant;
import java.util.UUID;

public final class RetentionEvents {

  private RetentionEvents() {}

  /**
   * {@code security.entry.purged} (docs/architecture/01 §3, 05 §5): the nightly retention job
   * removed gate records older than {@code before} for audit. Counts only, never content.
   */
  public record EntriesPurged(UUID societyId, Instant before, int retentionDays, int entries, int visitors,
      int deliveries, int staffVisits, int vehicleMovements) implements SecurityEvent {
    @Override public String type() { return "security.entry.purged"; }
    @Override public UUID aggregateId() { return societyId; }
  }
}
