package in.societyos.utility.checklist.domain;

import in.societyos.utility.platform.core.UuidV7;
import in.societyos.utility.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** A checklist a society runs: WTP morning round, DG weekly test, fire panel daily check, … */
@Entity
@Table(name = "checklist_template")
public class ChecklistTemplate extends TenantEntity {

  public static final Set<String> SYSTEMS = Set.of("WTP", "STP", "WATER", "PUMP", "TANK", "DG", "TRANSFORMER",
      "LIFT", "FIRE", "ENERGY", "HOUSEKEEPING", "SECURITY", "OTHER");
  public static final Set<String> FREQUENCIES = Set.of("DAILY", "PER_SHIFT", "WEEKLY", "MONTHLY");

  @Column(nullable = false, updatable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String system;

  @Column(nullable = false)
  private String frequency;

  @Column(name = "asset_id")
  private UUID assetId;

  @Column(name = "location_id")
  private UUID locationId;

  @Column(name = "items", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String itemsJson;

  @Column(nullable = false)
  private boolean active;

  protected ChecklistTemplate() {}

  public ChecklistTemplate(String code, String name, String system, String frequency, UUID assetId, UUID locationId,
      String itemsJson) {
    super(UuidV7.next());
    this.code = code;
    this.active = true;
    update(name, system, frequency, assetId, locationId, itemsJson);
  }

  public void update(String name, String system, String frequency, UUID assetId, UUID locationId, String itemsJson) {
    if (!SYSTEMS.contains(system)) {
      throw new IllegalArgumentException("system must be one of " + SYSTEMS);
    }
    if (!FREQUENCIES.contains(frequency)) {
      throw new IllegalArgumentException("frequency must be one of " + FREQUENCIES);
    }
    this.name = name;
    this.system = system;
    this.frequency = frequency;
    this.assetId = assetId;
    this.locationId = locationId;
    this.itemsJson = itemsJson;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public String getSystem() { return system; }
  public String getFrequency() { return frequency; }
  public UUID getAssetId() { return assetId; }
  public UUID getLocationId() { return locationId; }
  public String getItemsJson() { return itemsJson; }
  public boolean isActive() { return active; }
}
