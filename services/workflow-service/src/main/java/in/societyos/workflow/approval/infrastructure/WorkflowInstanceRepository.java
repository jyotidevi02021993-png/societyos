package in.societyos.workflow.approval.infrastructure;

import in.societyos.workflow.approval.domain.WorkflowInstance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {
  List<WorkflowInstance> findBySubjectTypeAndSubjectIdOrderByStartedAtDesc(String subjectType, UUID subjectId);
  List<WorkflowInstance> findByStatusOrderByStartedAtDesc(WorkflowInstance.Status status);
  List<WorkflowInstance> findAllByOrderByStartedAtDesc();
  Optional<WorkflowInstance> findBySubjectTypeAndSubjectIdAndStatus(String subjectType, UUID subjectId, WorkflowInstance.Status status);
}
