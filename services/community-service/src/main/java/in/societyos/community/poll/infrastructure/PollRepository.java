package in.societyos.community.poll.infrastructure;

import in.societyos.community.poll.domain.Poll;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PollRepository extends JpaRepository<Poll, UUID> {

  Optional<Poll> findByIdAndSocietyId(UUID id, UUID societyId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Poll p where p.id = :id and p.societyId = :society")
  Optional<Poll> lockById(@Param("id") UUID id, @Param("society") UUID society);

  List<Poll> findTop100BySocietyIdOrderByCreatedAtDesc(UUID societyId);

  List<Poll> findBySocietyIdAndStatusAndClosesAtLessThanEqual(UUID societyId, String status, Instant now);
}
