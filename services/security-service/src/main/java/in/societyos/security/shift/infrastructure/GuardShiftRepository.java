package in.societyos.security.shift.infrastructure;

import in.societyos.security.shift.domain.GuardShift;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardShiftRepository extends JpaRepository<GuardShift, UUID> {

  List<GuardShift> findBySocietyIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
      UUID societyId, Instant to, Instant from);

  List<GuardShift> findBySocietyIdAndGuardUserIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
      UUID societyId, UUID guardUserId, Instant to, Instant from);
}
