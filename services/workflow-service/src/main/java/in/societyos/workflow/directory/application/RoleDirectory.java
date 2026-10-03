package in.societyos.workflow.directory.application;

import in.societyos.workflow.directory.domain.RoleHolder;
import in.societyos.workflow.directory.infrastructure.RoleHolderRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who holds which role in a society, from identity-service events (for approver notifications). */
@Service
public class RoleDirectory {

  private final RoleHolderRepository roles;

  public RoleDirectory(RoleHolderRepository roles) {
    this.roles = roles;
  }

  @Transactional
  public void assigned(UUID assignmentId, UUID userId, String roleCode) {
    if (!roles.existsById(assignmentId)) {
      roles.save(new RoleHolder(assignmentId, userId, roleCode));
    }
  }

  @Transactional
  public void revoked(UUID assignmentId) {
    roles.findById(assignmentId).ifPresent(roles::delete);
  }

  @Transactional(readOnly = true)
  public List<UUID> holdersOf(String roleCode) {
    return roleCode == null ? List.of()
        : roles.findByRoleCode(roleCode).stream().map(RoleHolder::getUserId).distinct().toList();
  }
}
