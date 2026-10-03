package in.societyos.community.notice.infrastructure;

import in.societyos.community.notice.domain.Notice;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

  Optional<Notice> findByIdAndSocietyId(UUID id, UUID societyId);

  @Query("""
      select n from Notice n where n.societyId = :society and n.status in :statuses
      order by n.pinned desc, n.publishAt desc""")
  List<Notice> feed(@Param("society") UUID society, @Param("statuses") Collection<String> statuses, Pageable page);

  List<Notice> findBySocietyIdAndStatusAndPublishAtLessThanEqual(UUID society, String status, Instant now);
}
