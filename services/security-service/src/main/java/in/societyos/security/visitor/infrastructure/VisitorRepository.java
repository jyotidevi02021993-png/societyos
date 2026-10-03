package in.societyos.security.visitor.infrastructure;

import in.societyos.security.visitor.domain.Visitor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitorRepository extends JpaRepository<Visitor, UUID> {

  Optional<Visitor> findFirstBySocietyIdAndPhoneHashOrderByLastSeenAtDesc(UUID societyId, String phoneHash);

  @Modifying
  @org.springframework.transaction.annotation.Transactional
  @Query("delete from Visitor v where v.societyId = :societyId and v.lastSeenAt < :before")
  int purge(@Param("societyId") UUID societyId, @Param("before") Instant before);
}
