package in.societyos.community.directory.infrastructure;

import in.societyos.community.directory.domain.MembershipRef;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRefRepository extends JpaRepository<MembershipRef, UUID> {

  List<MembershipRef> findBySocietyIdAndUserIdAndActiveTrue(UUID societyId, UUID userId);

  boolean existsBySocietyIdAndUserIdAndFlatIdAndActiveTrue(UUID societyId, UUID userId, UUID flatId);

  List<MembershipRef> findBySocietyIdAndFlatIdInAndActiveTrue(UUID societyId, Collection<UUID> flatIds);

  List<MembershipRef> findBySocietyIdAndActiveTrue(UUID societyId);
}
