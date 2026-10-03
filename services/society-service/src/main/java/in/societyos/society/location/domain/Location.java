package in.societyos.society.location.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;

/**
 * A place in the society that assets, readings and job cards point at (pump room, basement,
 * electrical room ...). Locations form a tree through {@code parentId}; a tower is optional.
 */
@Entity
@Table(name = "location")
public class Location extends TenantEntity {

  public static final Set<String> KINDS = Set.of("PUMP_ROOM", "BASEMENT", "ELECTRICAL_ROOM", "PLANT_ROOM",
      "DG_ROOM", "STP", "WTP", "LIFT_ROOM", "TERRACE", "GATE", "COMMON_AREA", "PARKING", "GARDEN", "OTHER");

  @Column(nullable = false)
  private String kind;

  @Column(nullable = false)
  private String name;

  @Column(name = "tower_id")
  private UUID towerId;

  @Column(name = "parent_id")
  private UUID parentId;

  protected Location() {}

  public Location(String kind, String name, UUID towerId, UUID parentId) {
    super(UuidV7.next());
    update(kind, name, towerId, parentId);
  }

  public void update(String kind, String name, UUID towerId, UUID parentId) {
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("Unknown location kind " + kind);
    }
    this.kind = kind;
    this.name = name;
    this.towerId = towerId;
    this.parentId = parentId;
  }

  public String getKind() { return kind; }
  public String getName() { return name; }
  public UUID getTowerId() { return towerId; }
  public UUID getParentId() { return parentId; }
}
