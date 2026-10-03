package in.societyos.security.directory.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Who holds a gate-relevant staff role (from {@code identity.role.assigned/revoked}); id = role
 * assignment id. Used to address SOS and incident alerts to guards and managers.
 */
@Entity
@Table(name = "staff_role")
public class StaffRole extends TenantEntity {

  /** Roles that receive SOS and serious-incident alerts. */
  public static final Set<String> ALERTED = Set.of("GUARD", "ESTATE_MANAGER", "FACILITY_MANAGER");

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "role_code", nullable = false)
  private String roleCode;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected StaffRole() {}

  public StaffRole(UUID assignmentId, UUID userId, String roleCode) {
    super(assignmentId);
    this.userId = userId;
    this.roleCode = roleCode;
  }

  public void revoke(Instant at) {
    if (revokedAt == null) {
      revokedAt = at;
    }
  }

  public UUID getUserId() {
    return userId;
  }

  public String getRoleCode() {
    return roleCode;
  }
}
