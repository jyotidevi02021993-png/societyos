package in.societyos.identity.role.domain;

import in.societyos.identity.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** User × society × role. Resident roles follow flat memberships (source MEMBERSHIP). */
@Entity
@Table(name = "role_assignment")
public class RoleAssignment extends TenantEntity {

  public enum Source {
    MANUAL,
    MEMBERSHIP,
    BOOTSTRAP
  }

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(nullable = false)
  private String source;

  @Column(name = "source_ref")
  private UUID sourceRef;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected RoleAssignment() {}

  public static RoleAssignment of(UUID userId, UUID roleId, Source source, UUID sourceRef) {
    RoleAssignment a = new RoleAssignment();
    a.userId = userId;
    a.roleId = roleId;
    a.source = source.name();
    a.sourceRef = sourceRef;
    a.validFrom = Instant.now();
    return a;
  }

  public boolean isActive() {
    return revokedAt == null;
  }

  public void revoke() {
    if (revokedAt == null) {
      revokedAt = Instant.now();
    }
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getRoleId() {
    return roleId;
  }

  public Source getSource() {
    return Source.valueOf(source);
  }

  public UUID getSourceRef() {
    return sourceRef;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
