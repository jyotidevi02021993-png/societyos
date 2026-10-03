package in.societyos.workflow.sla.infrastructure;

import in.societyos.workflow.sla.domain.SlaPolicy;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {
  List<SlaPolicy> findBySubjectTypeAndActiveTrue(String subjectType);
  List<SlaPolicy> findAllByOrderBySubjectTypeAscPriorityAsc();
}
