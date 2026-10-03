package in.societyos.identity.role.infrastructure;

import in.societyos.identity.role.domain.RoleAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {

  Optional<RoleAssignment> findBySocietyIdAndUserIdAndRoleIdAndRevokedAtIsNull(
      UUID societyId, UUID userId, UUID roleId);

  List<RoleAssignment> findBySocietyIdAndUserIdAndRevokedAtIsNull(UUID societyId, UUID userId);

  List<RoleAssignment> findBySocietyIdAndRoleIdAndRevokedAtIsNull(UUID societyId, UUID roleId);

  List<RoleAssignment> findBySocietyIdAndSourceRefAndRevokedAtIsNull(UUID societyId, UUID sourceRef);

  /** Permissions of a user in one society: union of the permission bundles of active roles. */
  @Query(
      value =
          """
          select distinct unnest(r.permissions)
          from role_assignment ra join role r on r.id = ra.role_id
          where ra.society_id = :societyId and ra.user_id = :userId and ra.revoked_at is null
            and ra.valid_from <= now() and (ra.valid_to is null or ra.valid_to > now())
          """,
      nativeQuery = true)
  List<String> permissionsOf(@Param("societyId") UUID societyId, @Param("userId") UUID userId);
}
