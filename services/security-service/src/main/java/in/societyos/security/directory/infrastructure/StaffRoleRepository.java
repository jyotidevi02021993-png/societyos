package in.societyos.security.directory.infrastructure;

import in.societyos.security.directory.domain.StaffRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffRoleRepository extends JpaRepository<StaffRole, UUID> {

  List<StaffRole> findBySocietyIdAndRoleCodeInAndRevokedAtIsNull(UUID societyId, Collection<String> roleCodes);
}
