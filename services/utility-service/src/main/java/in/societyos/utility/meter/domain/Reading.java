package in.societyos.utility.meter.domain;

import in.societyos.utility.platform.core.UuidV7;
import in.societyos.utility.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** One reading. Immutable once written (the table is range-partitioned by {@code at}). */
@Entity
@Table(name = "reading")
public class Reading extends TenantEntity {

  public static final Set<String> SOURCES = Set.of("MANUAL", "IOT", "IMPORT");

  @Column(name = "meter_id", nullable = false, updatable = false)
  private UUID meterId;

  @Column(name = "asset_id", updatable = false)
  private UUID assetId;

  @Column(nullable = false, updatable = false)
  private String metric;

  @Column(nullable = false, updatable = false)
  private BigDecimal value;

  @Column(nullable = false, updatable = false)
  private String unit;

  @Column(nullable = false, updatable = false)
  private Instant at;

  @Column(updatable = false)
  private BigDecimal delta;

  @Column(nullable = false, updatable = false)
  private String source;

  @Column(name = "recorded_by", updatable = false)
  private UUID recordedBy;

  @Column(nullable = false, updatable = false)
  private boolean anomaly;

  @Column(name = "anomaly_reason", updatable = false)
  private String anomalyReason;

  @Column(name = "expected_min", updatable = false)
  private BigDecimal expectedMin;

  @Column(name = "expected_max", updatable = false)
  private BigDecimal expectedMax;

  @Column(updatable = false)
  private String note;

  @Column(name = "photo_media_id", updatable = false)
  private UUID photoMediaId;

  protected Reading() {}

  public Reading(Meter meter, BigDecimal value, Instant at, ReadingRules.Evaluation eval, String source,
      UUID recordedBy, String note, UUID photoMediaId) {
    super(UuidV7.next());
    this.meterId = meter.getId();
    this.assetId = meter.getAssetId();
    this.metric = meter.getMetric();
    this.unit = meter.getUnit();
    this.value = value;
    this.at = at;
    this.delta = eval.delta();
    this.anomaly = eval.anomaly();
    this.anomalyReason = eval.reason() == null ? null : eval.reason().name();
    this.expectedMin = meter.getExpectedMin();
    this.expectedMax = meter.getExpectedMax();
    this.source = source == null ? "MANUAL" : source;
    this.recordedBy = recordedBy;
    this.note = note;
    this.photoMediaId = photoMediaId;
  }

  public UUID getMeterId() { return meterId; }
  public UUID getAssetId() { return assetId; }
  public String getMetric() { return metric; }
  public BigDecimal getValue() { return value; }
  public String getUnit() { return unit; }
  public Instant getAt() { return at; }
  public BigDecimal getDelta() { return delta; }
  public String getSource() { return source; }
  public UUID getRecordedBy() { return recordedBy; }
  public boolean isAnomaly() { return anomaly; }
  public String getAnomalyReason() { return anomalyReason; }
  public BigDecimal getExpectedMin() { return expectedMin; }
  public BigDecimal getExpectedMax() { return expectedMax; }
  public String getNote() { return note; }
  public UUID getPhotoMediaId() { return photoMediaId; }
}
