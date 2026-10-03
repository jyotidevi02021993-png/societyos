package in.societyos.identity.role.infrastructure;

import in.societyos.identity.role.domain.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS limits every query to the tenant's societies; code is unique per society. */
public interface RoleRepository extends JpaRepository<Role, UUID> {

  Optional<Role> findBySocietyIdAndCode(UUID societyId, String code);

  List<Role> findBySocietyIdOrderByCode(UUID societyId);
}
