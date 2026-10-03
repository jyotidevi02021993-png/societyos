package in.societyos.society.facility.infrastructure;

import in.societyos.society.facility.domain.Facility;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityRepository extends JpaRepository<Facility, UUID> {
  List<Facility> findAllByOrderByNameAsc();

  Optional<Facility> findByNameIgnoreCase(String name);
}
