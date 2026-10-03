package in.societyos.notification.directory.domain;

import in.societyos.notification.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A role assignment in one society ({@code identity.role.assigned}); resolves {@code recipientRoles}. */
@Entity
@Table(name = "role_member")
public class RoleMember extends TenantEntity {

  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(name = "role_code", nullable = false) private String roleCode;

  protected RoleMember() {}

  public RoleMember(UUID assignmentId, UUID userId, String roleCode) {
    super(assignmentId);
    this.userId = userId;
    this.roleCode = roleCode;
  }

  public UUID getUserId() { return userId; }
  public String getRoleCode() { return roleCode; }
}
