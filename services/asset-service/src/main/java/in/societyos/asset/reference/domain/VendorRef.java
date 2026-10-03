package in.societyos.asset.reference.domain;

import in.societyos.asset.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of vendor-service vendors (id = vendor id), for AMC and warranty screens. */
@Entity
@Table(name = "vendor_ref")
public class VendorRef extends TenantEntity {

  private String code;

  @Column(nullable = false)
  private String name;

  private String status;

  protected VendorRef() {}

  public VendorRef(UUID vendorId, String code, String name, String status) {
    super(vendorId);
    update(code, name, status);
  }

  public void update(String code, String name, String status) {
    this.code = code;
    this.name = name == null || name.isBlank() ? "Vendor" : name;
    this.status = status;
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public String getStatus() { return status; }
}
