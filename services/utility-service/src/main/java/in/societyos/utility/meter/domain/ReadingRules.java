package in.societyos.utility.meter.domain;

import java.math.BigDecimal;

/**
 * Anomaly thresholds for readings, free of persistence so they can be unit tested.
 *
 * <ul>
 *   <li>INSTANT meters (tank level, pH, pressure, voltage, temperature): the value itself must
 *       lie within [expectedMin, expectedMax].
 *   <li>CUMULATIVE meters (water flow, kWh, DG running hours): the value may never go down
 *       (METER_ROLLBACK); the consumption since the previous reading (delta) must lie within
 *       [expectedMin, expectedMax] and not exceed maxDelta (CONSUMPTION_SPIKE).
 * </ul>
 * Bounds are optional; a missing bound is never violated.
 */
public final class ReadingRules {

  private ReadingRules() {}

  public enum Mode { CUMULATIVE, INSTANT }

  public enum Reason { BELOW_MIN, ABOVE_MAX, METER_ROLLBACK, CONSUMPTION_SPIKE }

  /** Outcome: delta (cumulative only), anomaly reason or null. */
  public record Evaluation(BigDecimal delta, Reason reason) {
    public boolean anomaly() {
      return reason != null;
    }
  }

  public static Evaluation evaluate(Mode mode, BigDecimal value, BigDecimal previous, BigDecimal expectedMin,
      BigDecimal expectedMax, BigDecimal maxDelta) {
    if (value == null) {
      throw new IllegalArgumentException("value is required");
    }
    if (mode == Mode.INSTANT) {
      return new Evaluation(null, outside(value, expectedMin, expectedMax));
    }
    if (value.signum() < 0) {
      throw new IllegalArgumentException("A cumulative meter cannot read below zero");
    }
    if (previous == null) {
      return new Evaluation(null, null); // first reading: nothing to compare with
    }
    BigDecimal delta = value.subtract(previous);
    if (delta.signum() < 0) {
      return new Evaluation(delta, Reason.METER_ROLLBACK);
    }
    if (maxDelta != null && delta.compareTo(maxDelta) > 0) {
      return new Evaluation(delta, Reason.CONSUMPTION_SPIKE);
    }
    return new Evaluation(delta, outside(delta, expectedMin, expectedMax));
  }

  private static Reason outside(BigDecimal v, BigDecimal min, BigDecimal max) {
    if (min != null && v.compareTo(min) < 0) {
      return Reason.BELOW_MIN;
    }
    if (max != null && v.compareTo(max) > 0) {
      return Reason.ABOVE_MAX;
    }
    return null;
  }
}
