package in.societyos.utility.meter.domain;

import in.societyos.utility.common.UtilityDomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ReadingEvents {

  private ReadingEvents() {}

  /** {@code utility.reading.recorded}. */
  public record ReadingRecorded(UUID readingId, UUID assetId, UUID meterId, String metric, BigDecimal value,
      String unit, Instant at) implements UtilityDomainEvent {
    @Override public String type() { return "utility.reading.recorded"; }
    @Override public UUID aggregateId() { return meterId; }
  }

  /**
   * {@code utility.reading.anomaly}. {@code meterId}, {@code reason} and {@code delta} are optional
   * additions to the catalogue payload.
   */
  public record ReadingAnomaly(UUID readingId, UUID assetId, String metric, BigDecimal value, BigDecimal expectedMin,
      BigDecimal expectedMax, UUID meterId, String reason, BigDecimal delta) implements UtilityDomainEvent {
    @Override public String type() { return "utility.reading.anomaly"; }
    @Override public UUID aggregateId() { return meterId; }
  }
}
