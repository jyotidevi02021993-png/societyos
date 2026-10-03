package in.societyos.ticket.jobcard.infrastructure;

import in.societyos.ticket.jobcard.domain.JobCard;
import in.societyos.ticket.jobcard.domain.JobCardStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobCardRepository extends JpaRepository<JobCard, UUID> {
  List<JobCard> findAllByOrderByCreatedAtDesc();
  List<JobCard> findByStatusOrderByCreatedAtAsc(JobCardStatus status);
  List<JobCard> findByAssigneeUserIdOrderByCreatedAtDesc(UUID assigneeUserId);
  List<JobCard> findByAssigneeUserIdAndStatusInOrderByCreatedAtAsc(UUID assigneeUserId, Collection<JobCardStatus> statuses);
  Optional<JobCard> findFirstBySourceTypeAndSourceIdAndStatusNot(String sourceType, UUID sourceId, JobCardStatus status);
  boolean existsBySourceTypeAndSourceIdAndStatusNot(String sourceType, UUID sourceId, JobCardStatus status);
}
