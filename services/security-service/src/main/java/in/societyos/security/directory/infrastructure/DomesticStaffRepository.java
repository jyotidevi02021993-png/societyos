package in.societyos.security.directory.infrastructure;

import in.societyos.security.directory.domain.DomesticStaff;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomesticStaffRepository extends JpaRepository<DomesticStaff, UUID> {

  Optional<DomesticStaff> findBySocietyIdAndPhoneHash(UUID societyId, String phoneHash);

  @Query(
      value = "select * from domestic_staff where society_id = :societyId and :flatId = any(flat_ids) order by name",
      nativeQuery = true)
  List<DomesticStaff> findByFlat(@Param("societyId") UUID societyId, @Param("flatId") UUID flatId);
}
