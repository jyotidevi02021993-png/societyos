package in.societyos.security.directory.infrastructure;

import in.societyos.security.directory.domain.FlatVehicle;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatVehicleRepository extends JpaRepository<FlatVehicle, UUID> {

  List<FlatVehicle> findBySocietyIdAndRegNoAndRemovedAtIsNull(UUID societyId, String regNo);

  List<FlatVehicle> findBySocietyIdAndFlatIdAndRemovedAtIsNull(UUID societyId, UUID flatId);

  List<FlatVehicle> findBySocietyIdAndRfidTagAndRemovedAtIsNull(UUID societyId, String rfidTag);
}
