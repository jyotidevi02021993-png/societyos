package in.societyos.society.household.infrastructure;

import in.societyos.society.household.domain.DomesticStaff;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DomesticStaffRepository extends JpaRepository<DomesticStaff, UUID> {
  List<DomesticStaff> findAllByOrderByNameAsc();

  @Query("select s from DomesticStaff s where :flatId member of s.flatIds order by s.name")
  List<DomesticStaff> findByFlat(UUID flatId);

  Optional<DomesticStaff> findByPhoneHash(String phoneHash);
}
