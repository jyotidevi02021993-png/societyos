package in.societyos.community.event.infrastructure;

import in.societyos.community.event.domain.CommunityEvent;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityEventRepository extends JpaRepository<CommunityEvent, UUID> {

  Optional<CommunityEvent> findByIdAndSocietyId(UUID id, UUID societyId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from CommunityEvent e where e.id = :id and e.societyId = :society")
  Optional<CommunityEvent> lockById(@Param("id") UUID id, @Param("society") UUID society);

  List<CommunityEvent> findTop200BySocietyIdAndEndsAtAfterOrderByStartsAtAsc(UUID societyId, Instant after);
}
