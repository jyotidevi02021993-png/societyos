package in.societyos.security.vehicle.infrastructure;

import in.societyos.security.vehicle.domain.VehicleMovement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleMovementRepository extends JpaRepository<VehicleMovement, UUID> {

  List<VehicleMovement> findBySocietyIdOrderByAtDesc(UUID societyId, Limit limit);

  List<VehicleMovement> findBySocietyIdAndFlatIdOrderByAtDesc(UUID societyId, UUID flatId, Limit limit);

  @Modifying
  @org.springframework.transaction.annotation.Transactional
  @Query("delete from VehicleMovement m where m.societyId = :societyId and m.at < :before")
  int purge(@Param("societyId") UUID societyId, @Param("before") Instant before);
}
