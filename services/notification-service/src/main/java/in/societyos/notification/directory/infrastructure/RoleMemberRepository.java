package in.societyos.notification.directory.infrastructure;

import in.societyos.notification.directory.domain.RoleMember;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleMemberRepository extends JpaRepository<RoleMember, UUID> {

  List<RoleMember> findBySocietyIdAndRoleCodeIn(UUID societyId, Collection<String> roleCodes);
}
