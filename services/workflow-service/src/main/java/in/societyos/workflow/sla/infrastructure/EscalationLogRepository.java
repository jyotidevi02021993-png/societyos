package in.societyos.workflow.sla.infrastructure;

import in.societyos.workflow.sla.domain.EscalationLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationLogRepository extends JpaRepository<EscalationLog, UUID> {
  List<EscalationLog> findBySubjectTypeAndSubjectIdOrderByAtAsc(String subjectType, UUID subjectId);
}
