package in.societyos.vendor.vendor.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A person deployed by a vendor (Agent Profile form). With a {@code userId} the person can use the
 * vendor portal, which then shows only this vendor's records.
 */
@Entity
@Table(name = "agent")
public class Agent extends TenantEntity {

  @Column(name = "vendor_id", updatable = false)
  private UUID vendorId;
  @Column(name = "user_id")
  private UUID userId;
  @Column(nullable = false, updatable = false)
  private String code;
  @Column(nullable = false)
  private String name;
  @Column(nullable = false)
  private String role;
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  private String[] skills = new String[0];
  @Column(nullable = false)
  private String status = "ACTIVE";

  protected Agent() {}

  public static Agent create(UUID vendorId, UUID userId, String code, String name, String role, List<String> skills) {
    if (code == null || name == null || role == null) {
      throw ProblemException.badRequest("INVALID_AGENT", "code, name and role are required");
    }
    Agent a = new Agent();
    a.vendorId = vendorId;
    a.userId = userId;
    a.code = code;
    a.name = name;
    a.role = role;
    a.skills = skills == null ? new String[0] : skills.toArray(String[]::new);
    return a;
  }

  public void deactivate() {
    status = "INACTIVE";
  }

  public boolean isActive() {
    return "ACTIVE".equals(status);
  }

  public UUID getVendorId() { return vendorId; }
  public UUID getUserId() { return userId; }
  public String getCode() { return code; }
  public String getName() { return name; }
  public String getRole() { return role; }
  public List<String> getSkills() { return List.of(skills); }
  public String getStatus() { return status; }
}
