package in.societyos.community.directory.infrastructure;

import in.societyos.community.directory.domain.FacilityRef;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilityRefRepository extends JpaRepository<FacilityRef, UUID> {

  List<FacilityRef> findBySocietyIdOrderByNameAsc(UUID societyId);

  Optional<FacilityRef> findByIdAndSocietyId(UUID id, UUID societyId);

  /** Serialises bookings of one facility, so capacity and weekly limits are checked race-free. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select f from FacilityRef f where f.id = :id and f.societyId = :societyId")
  Optional<FacilityRef> lockById(@Param("id") UUID id, @Param("societyId") UUID societyId);
}
