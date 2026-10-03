package in.societyos.workflow.definition.application;

import in.societyos.workflow.definition.domain.ApprovalPlan;
import in.societyos.workflow.definition.domain.WorkflowDefinition;
import in.societyos.workflow.definition.infrastructure.WorkflowDefinitionRepository;
import in.societyos.workflow.platform.core.error.ProblemException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Approval workflow definitions: one active version per subject kind. */
@Service
public class DefinitionService {

  public record ActiveDefinition(WorkflowDefinition definition, ApprovalPlan plan) {}

  private final WorkflowDefinitionRepository definitions;
  private final JsonMapper json;

  public DefinitionService(WorkflowDefinitionRepository definitions, JsonMapper json) {
    this.definitions = definitions;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public List<WorkflowDefinition> list() {
    return definitions.findAllByOrderByKindAscDefVersionDesc();
  }

  @Transactional(readOnly = true)
  public WorkflowDefinition get(UUID id) {
    return definitions.findById(id).orElseThrow(() -> ProblemException.notFound("definition", id));
  }

  /** Creates version 1, or the next version (the previous one is retired). */
  @Transactional
  public WorkflowDefinition publish(String kind, String name, ApprovalPlan plan) {
    String k = kind(kind);
    List<WorkflowDefinition> existing = definitions.findByKindOrderByDefVersionDesc(k);
    int next = existing.isEmpty() ? 1 : existing.getFirst().getDefVersion() + 1;
    existing.stream().filter(WorkflowDefinition::isActive).forEach(d -> {
      d.retire();
      definitions.saveAndFlush(d);
    });
    String n = name == null || name.isBlank() ? k + " approval" : name.trim();
    return definitions.save(new WorkflowDefinition(k, n, next, json.writeValueAsString(plan)));
  }

  @Transactional
  public void deactivate(UUID id) {
    WorkflowDefinition d = get(id);
    d.retire();
    definitions.save(d);
  }

  @Transactional(readOnly = true)
  public Optional<ActiveDefinition> active(String kind) {
    return definitions.findByKindAndActiveTrue(kind(kind)).map(d -> new ActiveDefinition(d, plan(d)));
  }

  public ApprovalPlan plan(WorkflowDefinition d) {
    return json.readValue(d.getDefinitionJson(), ApprovalPlan.class);
  }

  public static ApprovalPlan validated(ApprovalPlanFactory factory) {
    try {
      return factory.build();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw ProblemException.badRequest("INVALID_DEFINITION", e.getMessage());
    }
  }

  /** Builds a plan; its validation errors become 400 INVALID_DEFINITION. */
  @FunctionalInterface
  public interface ApprovalPlanFactory {
    ApprovalPlan build();
  }

  private static String kind(String kind) {
    if (kind == null || kind.isBlank()) {
      throw ProblemException.badRequest("KIND_REQUIRED", "kind (subject type) is required");
    }
    return kind.trim().toUpperCase(Locale.ROOT);
  }
}
