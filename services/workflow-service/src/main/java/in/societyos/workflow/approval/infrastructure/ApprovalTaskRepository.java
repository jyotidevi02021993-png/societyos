package in.societyos.workflow.approval.infrastructure;

import in.societyos.workflow.approval.domain.ApprovalTask;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, UUID> {
  List<ApprovalTask> findByInstanceIdOrderByStepAsc(UUID instanceId);

  @Query("select t from ApprovalTask t where t.status = in.societyos.workflow.approval.domain.ApprovalTask.Status.PENDING"
      + " and (t.approverUserId = ?1 or t.approverRole in ?2) order by t.createdAt asc")
  List<ApprovalTask> findInbox(UUID userId, Collection<String> roles);
}
