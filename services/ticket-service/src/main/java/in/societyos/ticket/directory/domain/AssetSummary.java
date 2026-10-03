package in.societyos.ticket.directory.domain;

import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of an asset-service asset (id = asset id). */
@Entity
@Table(name = "asset_summary")
public class AssetSummary extends TenantEntity {

  @Column(nullable = false)
  private String code;
  @Column(nullable = false)
  private String name;
  @Column(name = "location_id")
  private UUID locationId;
  private String status;

  protected AssetSummary() {}

  public AssetSummary(UUID assetId) {
    super(assetId);
  }

  public void update(String code, String name, UUID locationId, String status) {
    if (code != null) this.code = code;
    if (name != null) this.name = name;
    if (locationId != null) this.locationId = locationId;
    if (status != null) this.status = status;
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public UUID getLocationId() { return locationId; }
  public String getStatus() { return status; }
}
