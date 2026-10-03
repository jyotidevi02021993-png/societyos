package in.societyos.utility.checklist.infrastructure;

import in.societyos.utility.checklist.domain.ChecklistTemplate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, UUID> {
  boolean existsByCodeIgnoreCase(String code);
  List<ChecklistTemplate> findAllByOrderBySystemAscNameAsc();
  List<ChecklistTemplate> findByActiveTrueAndFrequencyInOrderByNameAsc(Collection<String> frequencies);
}
