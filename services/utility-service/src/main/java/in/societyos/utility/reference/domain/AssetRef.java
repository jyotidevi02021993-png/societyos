package in.societyos.utility.reference.domain;

import in.societyos.utility.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of asset-service assets (id = asset id), from {@code asset.asset.created/updated}. */
@Entity
@Table(name = "asset_ref")
public class AssetRef extends TenantEntity {

  private String code;

  @Column(nullable = false)
  private String name;

  @Column(name = "category_group")
  private String categoryGroup;

  @Column(name = "location_id")
  private UUID locationId;

  private String status;

  protected AssetRef() {}

  public AssetRef(UUID assetId, String code, String name, String categoryGroup, UUID locationId, String status) {
    super(assetId);
    update(code, name, categoryGroup, locationId, status);
  }

  public void update(String code, String name, String categoryGroup, UUID locationId, String status) {
    this.code = code;
    this.name = name == null || name.isBlank() ? "Asset" : name;
    this.categoryGroup = categoryGroup;
    this.locationId = locationId;
    this.status = status;
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public String getCategoryGroup() { return categoryGroup; }
  public UUID getLocationId() { return locationId; }
  public String getStatus() { return status; }
}
