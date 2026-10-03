package in.societyos.utility.meter.infrastructure;

import in.societyos.utility.meter.domain.Meter;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeterRepository extends JpaRepository<Meter, UUID> {
  boolean existsByCodeIgnoreCase(String code);
  List<Meter> findAllByOrderBySystemAscNameAsc();
  List<Meter> findBySystemOrderByNameAsc(String system);
}
