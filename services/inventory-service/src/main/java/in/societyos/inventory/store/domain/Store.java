package in.societyos.inventory.store.domain;

import in.societyos.inventory.platform.core.error.ProblemException;
import in.societyos.inventory.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A store room holding stock. One store per society is the default (receipts without a store go there). */
@Entity
@Table(name = "store")
public class Store extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String code;
  @Column(nullable = false)
  private String name;
  @Column(name = "location_id")
  private UUID locationId;
  @Column(name = "keeper_user_id")
  private UUID keeperUserId;
  @Column(name = "is_default", nullable = false)
  private boolean defaultStore;
  @Column(nullable = false)
  private String status = "ACTIVE";

  protected Store() {}

  public static Store create(String code, String name, UUID locationId, UUID keeperUserId, boolean defaultStore) {
    if (code == null || name == null) {
      throw ProblemException.badRequest("INVALID_STORE", "code and name are required");
    }
    Store s = new Store();
    s.code = code;
    s.name = name;
    s.locationId = locationId;
    s.keeperUserId = keeperUserId;
    s.defaultStore = defaultStore;
    return s;
  }

  public void update(String name, UUID locationId, UUID keeperUserId) {
    if (name == null) {
      throw ProblemException.badRequest("INVALID_STORE", "name is required");
    }
    this.name = name;
    this.locationId = locationId;
    this.keeperUserId = keeperUserId;
  }

  public void makeDefault(boolean value) {
    this.defaultStore = value;
  }

  public void deactivate() {
    if (defaultStore) {
      throw ProblemException.unprocessable("DEFAULT_STORE", "Make another store the default first");
    }
    status = "INACTIVE";
  }

  public void requireActive() {
    if (!"ACTIVE".equals(status)) {
      throw ProblemException.unprocessable("STORE_INACTIVE", "Store " + code + " is inactive");
    }
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public UUID getLocationId() { return locationId; }
  public UUID getKeeperUserId() { return keeperUserId; }
  public boolean isDefaultStore() { return defaultStore; }
  public String getStatus() { return status; }
}
