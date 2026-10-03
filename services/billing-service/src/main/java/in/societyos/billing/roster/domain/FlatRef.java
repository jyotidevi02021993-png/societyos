package in.societyos.billing.roster.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A flat of the billing roster (from {@code society.flat.created/updated}); id = flat id. */
@Entity
@Table(name = "flat_ref")
public class FlatRef extends TenantEntity {

  @Column(name = "tower_id")
  private UUID towerId;

  @Column(name = "tower_name")
  private String towerName;

  private String number;

  @Column(nullable = false)
  private String label;

  private Integer floor;

  @Column(name = "area_sqft")
  private Integer areaSqft;

  @Column(name = "flat_type")
  private String flatType;

  @Column(nullable = false)
  private String status = "VACANT";

  protected FlatRef() {}

  public FlatRef(UUID flatId) {
    super(flatId);
  }

  public void apply(UUID towerId, String towerName, String number, String label, Integer floor, Integer areaSqft,
      String flatType, String status) {
    this.towerId = towerId;
    this.towerName = towerName;
    this.number = number;
    this.label = label == null || label.isBlank() ? (number == null ? "?" : number) : label;
    this.floor = floor;
    this.areaSqft = areaSqft;
    this.flatType = flatType == null || flatType.isBlank() ? null : flatType.trim().toUpperCase();
    this.status = status == null ? "VACANT" : status;
  }

  public UUID getTowerId() { return towerId; }
  public String getTowerName() { return towerName; }
  public String getNumber() { return number; }
  public String getLabel() { return label; }
  public Integer getFloor() { return floor; }
  public Integer getAreaSqft() { return areaSqft; }
  public String getFlatType() { return flatType; }
  public String getStatus() { return status; }
}
