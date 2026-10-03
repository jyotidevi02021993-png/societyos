package in.societyos.ticket.directory.domain;

import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of a society-service flat (id = flat id). */
@Entity
@Table(name = "flat_directory")
public class FlatRef extends TenantEntity {

  @Column(nullable = false)
  private String label;
  @Column(name = "tower_name")
  private String towerName;
  private String status;

  protected FlatRef() {}

  public FlatRef(UUID flatId) {
    super(flatId);
  }

  public void update(String label, String towerName, String status) {
    this.label = label;
    this.towerName = towerName;
    this.status = status;
  }

  public String getLabel() { return label; }
  public String getTowerName() { return towerName; }
  public String getStatus() { return status; }
}
