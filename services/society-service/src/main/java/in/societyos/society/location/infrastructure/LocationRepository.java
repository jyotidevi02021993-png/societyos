package in.societyos.society.location.infrastructure;

import in.societyos.society.location.domain.Location;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {
  List<Location> findAllByOrderByNameAsc();

  boolean existsByParentIdAndNameIgnoreCase(UUID parentId, String name);

  boolean existsByParentIdIsNullAndNameIgnoreCase(String name);
}
