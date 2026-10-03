package in.societyos.community.directory.infrastructure;

import in.societyos.community.directory.domain.FlatRef;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatRefRepository extends JpaRepository<FlatRef, UUID> {

  List<FlatRef> findBySocietyIdAndIdIn(UUID societyId, Collection<UUID> ids);

  List<FlatRef> findBySocietyIdAndTowerIdIn(UUID societyId, Collection<UUID> towerIds);
}
