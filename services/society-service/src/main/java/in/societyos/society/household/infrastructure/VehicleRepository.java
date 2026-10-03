package in.societyos.society.household.infrastructure;

import in.societyos.society.household.domain.Vehicle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
  List<Vehicle> findByRemovedAtIsNullOrderByRegNoAsc();

  List<Vehicle> findByFlatIdAndRemovedAtIsNullOrderByRegNoAsc(UUID flatId);

  Optional<Vehicle> findByRegNoAndRemovedAtIsNull(String regNo);

  Optional<Vehicle> findByRfidTagAndRemovedAtIsNull(String rfidTag);
}
