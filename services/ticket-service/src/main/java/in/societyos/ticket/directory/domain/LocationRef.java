package in.societyos.ticket.directory.domain;

import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of a society-service location (id = location id). */
@Entity
@Table(name = "location_ref")
public class LocationRef extends TenantEntity {

  private String kind;
  @Column(nullable = false)
  private String name;

  protected LocationRef() {}

  public LocationRef(UUID locationId) {
    super(locationId);
  }

  public void update(String kind, String name) {
    this.kind = kind;
    this.name = name;
  }

  public String getKind() { return kind; }
  public String getName() { return name; }
}
