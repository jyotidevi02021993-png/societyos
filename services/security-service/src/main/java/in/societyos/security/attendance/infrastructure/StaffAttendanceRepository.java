package in.societyos.security.attendance.infrastructure;

import in.societyos.security.attendance.domain.StaffAttendance;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffAttendanceRepository extends JpaRepository<StaffAttendance, UUID> {

  Optional<StaffAttendance> findBySocietyIdAndStaffIdAndOutAtIsNull(UUID societyId, UUID staffId);

  List<StaffAttendance> findBySocietyIdAndInAtGreaterThanEqualAndInAtLessThanOrderByInAtDesc(
      UUID societyId, Instant from, Instant to, Limit limit);

  List<StaffAttendance> findBySocietyIdAndStaffIdInAndInAtGreaterThanEqualAndInAtLessThanOrderByInAtDesc(
      UUID societyId, Collection<UUID> staffIds, Instant from, Instant to, Limit limit);

  List<StaffAttendance> findBySocietyIdAndOutAtIsNullOrderByInAtAsc(UUID societyId);

  @Modifying
  @org.springframework.transaction.annotation.Transactional
  @Query("delete from StaffAttendance a where a.societyId = :societyId and a.inAt < :before")
  int purge(@Param("societyId") UUID societyId, @Param("before") Instant before);
}
