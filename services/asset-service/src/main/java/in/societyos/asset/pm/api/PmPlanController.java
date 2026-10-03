package in.societyos.asset.pm.api;

import in.societyos.asset.pm.application.PmPlanService;
import in.societyos.asset.pm.application.PmTaskService;
import in.societyos.asset.pm.domain.Checklist;
import in.societyos.asset.pm.domain.PmPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/pm-plans")
public class PmPlanController {

  private final PmPlanService plans;
  private final PmTaskService tasks;

  public PmPlanController(PmPlanService plans, PmTaskService tasks) {
    this.plans = plans;
    this.tasks = tasks;
  }

  public record PlanRequest(UUID assetId, @NotBlank @Size(max = 160) String name, @NotBlank String frequency,
      LocalDate anchorOn, Integer leadDays, String usageMetric, BigDecimal usageInterval,
      List<Checklist.Item> checklist, UUID checklistTemplateId, UUID assigneeUserId, UUID vendorId) {
    PmPlanService.PlanInput toInput() {
      return new PmPlanService.PlanInput(name, frequency, anchorOn, leadDays, usageMetric, usageInterval, checklist,
          checklistTemplateId, assigneeUserId, vendorId);
    }
  }

  public record ManualTaskRequest(LocalDate dueOn) {}

  public record PlanResponse(UUID id, UUID assetId, String name, String frequency, LocalDate anchorOn, int leadDays,
      LocalDate nextDueOn, String usageMetric, BigDecimal usageInterval, BigDecimal lastDoneUsage,
      BigDecimal latestUsage, List<Checklist.Item> checklist, UUID checklistTemplateId, UUID assigneeUserId,
      UUID vendorId, boolean active) {
    public static PlanResponse from(PmPlanService.PlanView v) {
      PmPlan p = v.plan();
      return new PlanResponse(p.getId(), p.getAssetId(), p.getName(), p.getFrequency().name(), p.getAnchorOn(),
          p.getLeadDays(), p.getNextDueOn(), p.getUsageMetric(), p.getUsageInterval(), p.getLastDoneUsage(),
          p.getLatestUsage(), v.checklist(), p.getChecklistTemplateId(), p.getAssigneeUserId(), p.getVendorId(),
          p.isActive());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('pm:manage', 'asset:view')")
  public List<PlanResponse> list(@RequestParam(required = false) UUID assetId) {
    return plans.list(assetId).stream().map(PlanResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('pm:manage', 'asset:view')")
  public PlanResponse get(@PathVariable UUID id) {
    return PlanResponse.from(plans.get(id));
  }

  @PostMapping
  @PreAuthorize("@perm.has('pm:manage')")
  public ResponseEntity<PlanResponse> create(@Valid @RequestBody PlanRequest r) {
    if (r.assetId() == null) {
      throw in.societyos.asset.platform.core.error.ProblemException.badRequest("ASSET_REQUIRED", "assetId is required");
    }
    PlanResponse saved = PlanResponse.from(plans.create(r.assetId(), r.toInput()));
    return ResponseEntity.created(URI.create("/v1/pm-plans/" + saved.id())).body(saved);
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('pm:manage')")
  public PlanResponse update(@PathVariable UUID id, @Valid @RequestBody PlanRequest r) {
    return PlanResponse.from(plans.update(id, r.toInput()));
  }

  @PostMapping("/{id}/activate")
  @PreAuthorize("@perm.has('pm:manage')")
  public PlanResponse activate(@PathVariable UUID id) {
    return PlanResponse.from(plans.setActive(id, true));
  }

  @PostMapping("/{id}/deactivate")
  @PreAuthorize("@perm.has('pm:manage')")
  public PlanResponse deactivate(@PathVariable UUID id) {
    return PlanResponse.from(plans.setActive(id, false));
  }

  /** An extra, manually raised occurrence of the plan. */
  @PostMapping("/{id}/tasks")
  @PreAuthorize("@perm.has('pm:manage')")
  public ResponseEntity<PmTaskController.TaskResponse> raiseTask(@PathVariable UUID id,
      @RequestBody(required = false) @Valid ManualTaskRequest r) {
    PmTaskController.TaskResponse saved = PmTaskController.TaskResponse.from(
        tasks.createManual(id, r == null ? null : r.dueOn()));
    return ResponseEntity.created(URI.create("/v1/pm-tasks/" + saved.id())).body(saved);
  }
}
