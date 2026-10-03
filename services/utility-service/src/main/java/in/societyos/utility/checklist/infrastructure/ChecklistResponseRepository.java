package in.societyos.utility.checklist.infrastructure;

import in.societyos.utility.checklist.domain.ChecklistResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistResponseRepository extends JpaRepository<ChecklistResponse, UUID> {
  List<ChecklistResponse> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
