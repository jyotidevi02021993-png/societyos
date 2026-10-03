package in.societyos.asset.asset.domain;

import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One line on an asset's timeline. Rows are append-only. */
@Entity
@Table(name = "asset_history")
public class AssetHistory extends TenantEntity {

  public enum Kind {
    CREATED, UPDATED, STATUS_CHANGED, BREAKDOWN, REPAIR, PM_DONE, PM_MISSED,
    WARRANTY, AMC, AMC_VISIT, QR, DISPOSED, NOTE
  }

  @Column(name = "asset_id", nullable = false, updatable = false)
  private UUID assetId;

  @Column(nullable = false, updatable = false)
  private Instant at;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private Kind kind;

  @Column(name = "ref_type", updatable = false)
  private String refType;

  @Column(name = "ref_id", updatable = false)
  private UUID refId;

  @Column(nullable = false, updatable = false)
  private String summary;

  @Column(name = "cost_paise", nullable = false, updatable = false)
  private long costPaise;

  @Column(name = "actor_id", updatable = false)
  private UUID actorId;

  protected AssetHistory() {}

  public AssetHistory(UUID assetId, Instant at, Kind kind, String refType, UUID refId, String summary,
      long costPaise, UUID actorId) {
    super(UuidV7.next());
    this.assetId = assetId;
    this.at = at;
    this.kind = kind;
    this.refType = refType;
    this.refId = refId;
    this.summary = summary.length() > 500 ? summary.substring(0, 500) : summary;
    this.costPaise = Math.max(0, costPaise);
    this.actorId = actorId;
  }

  public UUID getAssetId() { return assetId; }
  public Instant getAt() { return at; }
  public Kind getKind() { return kind; }
  public String getRefType() { return refType; }
  public UUID getRefId() { return refId; }
  public String getSummary() { return summary; }
  public long getCostPaise() { return costPaise; }
  public UUID getActorId() { return actorId; }
}
