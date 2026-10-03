package in.societyos.ticket.directory.domain;

import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read model of an identity-service role assignment (id = assignment id): escalation recipients. */
@Entity
@Table(name = "role_holder")
public class RoleHolder extends TenantEntity {

  @Column(name = "user_id", nullable = false)
  private UUID userId;
  @Column(name = "role_code", nullable = false)
  private String roleCode;

  protected RoleHolder() {}

  public RoleHolder(UUID assignmentId, UUID userId, String roleCode) {
    super(assignmentId);
    this.userId = userId;
    this.roleCode = roleCode;
  }

  public UUID getUserId() { return userId; }
  public String getRoleCode() { return roleCode; }
}
