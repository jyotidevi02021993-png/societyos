package in.societyos.ticket.jobcard.infrastructure;

import in.societyos.ticket.jobcard.domain.JobCardSpare;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobCardSpareRepository extends JpaRepository<JobCardSpare, UUID> {
  List<JobCardSpare> findByJobCardIdOrderByCreatedAtAsc(UUID jobCardId);
  boolean existsByIssueId(UUID issueId);
}
