package in.societyos.utility.checklist.infrastructure;

import in.societyos.utility.checklist.domain.ChecklistRun;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistRunRepository extends JpaRepository<ChecklistRun, UUID> {
  Optional<ChecklistRun> findByTemplateIdAndRunDateAndShift(UUID templateId, LocalDate runDate, String shift);
  List<ChecklistRun> findByRunDateOrderByStartedAtAsc(LocalDate runDate);
}
