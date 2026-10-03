package in.societyos.workflow.sla.infrastructure;

import in.societyos.workflow.sla.domain.SlaTimer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaTimerRepository extends JpaRepository<SlaTimer, UUID> {
  List<SlaTimer> findBySubjectTypeAndSubjectIdOrderByStartedAtAsc(String subjectType, UUID subjectId);
  List<SlaTimer> findByStatusOrderByDueAtAsc(SlaTimer.Status status);
}
