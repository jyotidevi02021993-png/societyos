package in.societyos.security.entry.infrastructure;

import in.societyos.security.entry.domain.EntryLog;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EntryLogRepository extends JpaRepository<EntryLog, UUID>, JpaSpecificationExecutor<EntryLog> {

  List<EntryLog> findBySocietyIdAndStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
      UUID societyId, String status, Instant now, Limit limit);

  List<EntryLog> findBySocietyIdAndStatusOrderByRequestedAtAsc(UUID societyId, String status, Limit limit);

  Optional<EntryLog> findBySocietyIdAndClientEntryId(UUID societyId, String clientEntryId);

  @Modifying
  @org.springframework.transaction.annotation.Transactional
  @Query("delete from EntryLog e where e.societyId = :societyId and e.requestedAt < :before")
  int purge(@Param("societyId") UUID societyId, @Param("before") Instant before);
}
