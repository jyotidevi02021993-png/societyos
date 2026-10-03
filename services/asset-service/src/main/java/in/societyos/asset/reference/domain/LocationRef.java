package in.societyos.asset.reference.domain;

import in.societyos.asset.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of society-service locations (id = location id), from {@code society.location.created}. */
@Entity
@Table(name = "location_ref")
public class LocationRef extends TenantEntity {

  private String kind;

  @Column(nullable = false)
  private String name;

  @Column(name = "tower_id")
  private UUID towerId;

  @Column(name = "parent_id")
  private UUID parentId;

  protected LocationRef() {}

  public LocationRef(UUID locationId, String kind, String name, UUID towerId, UUID parentId) {
    super(locationId);
    update(kind, name, towerId, parentId);
  }

  public void update(String kind, String name, UUID towerId, UUID parentId) {
    this.kind = kind;
    this.name = name == null || name.isBlank() ? "Location" : name;
    this.towerId = towerId;
    this.parentId = parentId;
  }

  public String getKind() { return kind; }
  public String getName() { return name; }
  public UUID getTowerId() { return towerId; }
  public UUID getParentId() { return parentId; }
}
