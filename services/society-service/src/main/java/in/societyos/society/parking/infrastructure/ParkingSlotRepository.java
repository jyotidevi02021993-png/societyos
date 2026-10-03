package in.societyos.society.parking.infrastructure;

import in.societyos.society.parking.domain.ParkingSlot;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSlotRepository extends JpaRepository<ParkingSlot, UUID> {
  List<ParkingSlot> findAllByOrderByCodeAsc();

  List<ParkingSlot> findByFlatIdOrderByCodeAsc(UUID flatId);

  boolean existsByCodeIgnoreCase(String code);
}
