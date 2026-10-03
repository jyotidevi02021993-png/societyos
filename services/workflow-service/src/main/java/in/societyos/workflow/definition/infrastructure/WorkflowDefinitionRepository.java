package in.societyos.workflow.definition.infrastructure;

import in.societyos.workflow.definition.domain.WorkflowDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {
  Optional<WorkflowDefinition> findByKindAndActiveTrue(String kind);
  List<WorkflowDefinition> findByKindOrderByDefVersionDesc(String kind);
  List<WorkflowDefinition> findAllByOrderByKindAscDefVersionDesc();
}
