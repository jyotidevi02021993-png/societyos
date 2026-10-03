package in.societyos.utility.meter.domain;

import in.societyos.utility.platform.core.UuidV7;
import in.societyos.utility.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** A reading point: a water or energy meter, DG hour meter, tank level, STP pH probe, … */
@Entity
@Table(name = "meter")
public class Meter extends TenantEntity {

  public static final Set<String> SYSTEMS =
      Set.of("WTP", "STP", "WATER", "PUMP", "TANK", "DG", "TRANSFORMER", "LIFT", "FIRE", "ENERGY", "OTHER");

  @Column(nullable = false, updatable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String system;

  @Column(name = "asset_id")
  private UUID assetId;

  @Column(name = "location_id")
  private UUID locationId;

  @Column(nullable = false)
  private String metric;

  @Column(nullable = false)
  private String unit;

  @Enumerated(EnumType.STRING)
  @Column(name = "reading_mode", nullable = false)
  private ReadingRules.Mode mode;

  @Column(name = "expected_min")
  private BigDecimal expectedMin;

  @Column(name = "expected_max")
  private BigDecimal expectedMax;

  @Column(name = "max_delta")
  private BigDecimal maxDelta;

  @Column(nullable = false)
  private boolean active;

  protected Meter() {}

  public record Details(String name, String system, UUID assetId, UUID locationId, String metric, String unit,
      ReadingRules.Mode mode, BigDecimal expectedMin, BigDecimal expectedMax, BigDecimal maxDelta) {
    public Details {
      if (name == null || name.isBlank() || metric == null || metric.isBlank() || unit == null || unit.isBlank()) {
        throw new IllegalArgumentException("name, metric and unit are required");
      }
      if (system == null || !SYSTEMS.contains(system)) {
        throw new IllegalArgumentException("system must be one of " + SYSTEMS);
      }
      if (mode == null) {
        throw new IllegalArgumentException("mode must be CUMULATIVE or INSTANT");
      }
      if (expectedMin != null && expectedMax != null && expectedMin.compareTo(expectedMax) > 0) {
        throw new IllegalArgumentException("expectedMin cannot be above expectedMax");
      }
      if (maxDelta != null && (mode == ReadingRules.Mode.INSTANT || maxDelta.signum() <= 0)) {
        throw new IllegalArgumentException("maxDelta applies to cumulative meters and must be positive");
      }
      metric = metric.trim().toUpperCase(java.util.Locale.ROOT);
    }
  }

  public Meter(String code, Details d) {
    super(UuidV7.next());
    this.code = code;
    this.active = true;
    update(d);
  }

  public void update(Details d) {
    this.name = d.name().trim();
    this.system = d.system();
    this.assetId = d.assetId();
    this.locationId = d.locationId();
    this.metric = d.metric();
    this.unit = d.unit().trim();
    this.mode = d.mode();
    this.expectedMin = d.expectedMin();
    this.expectedMax = d.expectedMax();
    this.maxDelta = d.maxDelta();
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public ReadingRules.Evaluation evaluate(BigDecimal value, BigDecimal previous) {
    return ReadingRules.evaluate(mode, value, previous, expectedMin, expectedMax, maxDelta);
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public String getSystem() { return system; }
  public UUID getAssetId() { return assetId; }
  public UUID getLocationId() { return locationId; }
  public String getMetric() { return metric; }
  public String getUnit() { return unit; }
  public ReadingRules.Mode getMode() { return mode; }
  public BigDecimal getExpectedMin() { return expectedMin; }
  public BigDecimal getExpectedMax() { return expectedMax; }
  public BigDecimal getMaxDelta() { return maxDelta; }
  public boolean isActive() { return active; }
}
