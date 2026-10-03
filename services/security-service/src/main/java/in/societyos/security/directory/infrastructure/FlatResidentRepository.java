package in.societyos.security.directory.infrastructure;

import in.societyos.security.directory.domain.FlatResident;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatResidentRepository extends JpaRepository<FlatResident, UUID> {

  List<FlatResident> findBySocietyIdAndFlatIdAndEndedAtIsNull(UUID societyId, UUID flatId);

  List<FlatResident> findBySocietyIdAndFlatIdInAndEndedAtIsNull(UUID societyId, java.util.Collection<UUID> flatIds);

  List<FlatResident> findBySocietyIdAndUserIdAndEndedAtIsNull(UUID societyId, UUID userId);

  boolean existsBySocietyIdAndFlatIdAndUserIdAndEndedAtIsNull(UUID societyId, UUID flatId, UUID userId);
}
