package in.societyos.workflow.approval.infrastructure;

import in.societyos.workflow.approval.domain.ApprovalDecision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> {
  boolean existsByTaskIdAndDecidedBy(UUID taskId, UUID decidedBy);
  List<ApprovalDecision> findByTaskIdOrderByDecidedAtAsc(UUID taskId);
}
