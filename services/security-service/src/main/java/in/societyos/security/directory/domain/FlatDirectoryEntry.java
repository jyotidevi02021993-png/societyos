package in.societyos.security.directory.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of a flat (from {@code society.flat.created/updated}); id = society-service flat id. */
@Entity
@Table(name = "flat_directory")
public class FlatDirectoryEntry extends TenantEntity {

  @Column(name = "tower_id")
  private UUID towerId;

  @Column(name = "tower_name")
  private String towerName;

  @Column(nullable = false)
  private String number;

  @Column(nullable = false)
  private String label;

  private Integer floor;

  @Column(nullable = false)
  private String status = "OCCUPIED";

  protected FlatDirectoryEntry() {}

  public FlatDirectoryEntry(UUID flatId) {
    super(flatId);
  }

  public void apply(UUID towerId, String towerName, String number, String label, Integer floor, String status) {
    this.towerId = towerId;
    this.towerName = towerName;
    this.number = number == null ? "" : number;
    this.label = label != null ? label : (towerName == null ? this.number : towerName + "-" + this.number);
    this.floor = floor;
    if (status != null) {
      this.status = status;
    }
  }

  public UUID getTowerId() {
    return towerId;
  }

  public String getTowerName() {
    return towerName;
  }

  public String getNumber() {
    return number;
  }

  public String getLabel() {
    return label;
  }

  public Integer getFloor() {
    return floor;
  }

  public String getStatus() {
    return status;
  }
}
