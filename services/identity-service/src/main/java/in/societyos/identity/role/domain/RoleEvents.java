package in.societyos.identity.role.domain;

import in.societyos.identity.platform.events.DomainEvent;
import java.util.UUID;

/** Role events consumed by gate/maintenance (assignee lists), audit, and permission caches. */
public final class RoleEvents {

  private RoleEvents() {}

  /** {@code identity.role.assigned} */
  public record RoleAssigned(UUID assignmentId, UUID userId, String roleCode, String source)
      implements DomainEvent {
    @Override
    public String type() {
      return "identity.role.assigned";
    }

    @Override
    public String context() {
      return "identity";
    }

    @Override
    public UUID aggregateId() {
      return userId;
    }
  }

  /** {@code identity.role.revoked} */
  public record RoleRevoked(UUID assignmentId, UUID userId, String roleCode) implements DomainEvent {
    @Override
    public String type() {
      return "identity.role.revoked";
    }

    @Override
    public String context() {
      return "identity";
    }

    @Override
    public UUID aggregateId() {
      return userId;
    }
  }

  /** {@code identity.role.updated}: a role's permission bundle changed. */
  public record RoleUpdated(UUID roleId, String roleCode, java.util.List<String> permissions)
      implements DomainEvent {
    @Override
    public String type() {
      return "identity.role.updated";
    }

    @Override
    public String context() {
      return "identity";
    }

    @Override
    public UUID aggregateId() {
      return roleId;
    }
  }
}
