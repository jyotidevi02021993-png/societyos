package in.societyos.ticket.jobcard.infrastructure;

import in.societyos.ticket.jobcard.domain.JobCardLabour;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobCardLabourRepository extends JpaRepository<JobCardLabour, UUID> {
  List<JobCardLabour> findByJobCardIdOrderByLoggedAtAsc(UUID jobCardId);
}
