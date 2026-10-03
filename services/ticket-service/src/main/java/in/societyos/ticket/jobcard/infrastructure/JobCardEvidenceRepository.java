package in.societyos.ticket.jobcard.infrastructure;

import in.societyos.ticket.jobcard.domain.JobCardEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobCardEvidenceRepository extends JpaRepository<JobCardEvidence, UUID> {
  List<JobCardEvidence> findByJobCardIdOrderByTakenAtAsc(UUID jobCardId);
  boolean existsByJobCardIdAndStage(UUID jobCardId, String stage);
  boolean existsByJobCardIdAndMediaId(UUID jobCardId, UUID mediaId);
}
