package in.societyos.workflow.definition.api;

import in.societyos.workflow.definition.application.DefinitionService;
import in.societyos.workflow.definition.domain.ApprovalPlan;
import in.societyos.workflow.definition.domain.WorkflowDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/definitions}: POST publishes a new version for the kind (the previous one is retired). */
@RestController
@RequestMapping("/v1/definitions")
class DefinitionController {

  private final DefinitionService definitions;

  DefinitionController(DefinitionService definitions) {
    this.definitions = definitions;
  }

  record StepRequest(String name, String approverRole, UUID approverUserId, Long appliesAbovePaise,
      Integer requiredApprovals, Integer escalateAfterMins, String escalateToRole) {
    ApprovalPlan.Step step() {
      return new ApprovalPlan.Step(name, approverRole == null ? null : approverRole.trim().toUpperCase(),
          approverUserId, appliesAbovePaise == null ? 0 : appliesAbovePaise,
          requiredApprovals == null ? 1 : requiredApprovals, escalateAfterMins,
          escalateToRole == null ? null : escalateToRole.trim().toUpperCase());
    }
  }

  record DefinitionRequest(@NotBlank String kind, String name, @PositiveOrZero long thresholdPaise,
      @NotEmpty List<StepRequest> steps) {}

  record DefinitionResponse(UUID id, String kind, String name, int version, boolean active, ApprovalPlan plan) {}

  private DefinitionResponse view(WorkflowDefinition d) {
    return new DefinitionResponse(d.getId(), d.getKind(), d.getName(), d.getDefVersion(), d.isActive(),
        definitions.plan(d));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('workflow:manage', 'approval:decide')")
  List<DefinitionResponse> list() {
    return definitions.list().stream().map(this::view).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('workflow:manage', 'approval:decide')")
  DefinitionResponse get(@PathVariable UUID id) {
    return view(definitions.get(id));
  }

  @PostMapping
  @PreAuthorize("@perm.has('workflow:manage')")
  ResponseEntity<DefinitionResponse> publish(@Valid @RequestBody DefinitionRequest r) {
    ApprovalPlan plan = DefinitionService.validated(
        () -> new ApprovalPlan(r.thresholdPaise(), r.steps().stream().map(StepRequest::step).toList()));
    WorkflowDefinition d = definitions.publish(r.kind(), r.name(), plan);
    return ResponseEntity.created(URI.create("/v1/definitions/" + d.getId())).body(view(d));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@perm.has('workflow:manage')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deactivate(@PathVariable UUID id) {
    definitions.deactivate(id);
  }
}
