package in.societyos.asset.pm.api;

import in.societyos.asset.jobs.application.DailyAssetJobs;
import in.societyos.asset.platform.core.tenant.TenantContext;
import in.societyos.asset.platform.web.CursorPage;
import in.societyos.asset.pm.application.PmTaskService;
import in.societyos.asset.pm.domain.Checklist;
import in.societyos.asset.pm.domain.PmTask;
import in.societyos.asset.reference.application.ReferenceData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/pm-tasks")
public class PmTaskController {

  private final PmTaskService tasks;
  private final DailyAssetJobs jobs;
  private final ReferenceData reference;

  public PmTaskController(PmTaskService tasks, DailyAssetJobs jobs, ReferenceData reference) {
    this.tasks = tasks;
    this.jobs = jobs;
    this.reference = reference;
  }

  public record CompleteRequest(List<Checklist.Result> results, @Size(max = 2000) String remarks,
      List<UUID> photoMediaIds, Long costPaise, BigDecimal usageReading, Boolean raiseBreakdown) {}

  public record AssignRequest(@NotNull UUID userId) {}

  public record GenerateResponse(LocalDate date, int pmTasksGenerated, int pmTasksOverdue, int expiryAlerts) {}

  /** A PM task without its checklist, for lists and sync. */
  public record TaskSummary(UUID id, String number, UUID pmPlanId, UUID assetId, LocalDate dueOn, String trigger,
      String status, UUID assigneeUserId, UUID jobCardId, Instant completedAt, int okCount, int failedCount,
      long costPaise, Instant updatedAt) {
    public static TaskSummary from(PmTask t) {
      return new TaskSummary(t.getId(), t.getNumber(), t.getPmPlanId(), t.getAssetId(), t.getDueOn(),
          t.getTrigger().name(), t.getStatus().name(), t.getAssigneeUserId(), t.getJobCardId(), t.getCompletedAt(),
          t.getOkCount(), t.getFailedCount(), t.getCostPaise(), t.getUpdatedAt());
    }
  }

  public record TaskResponse(UUID id, String number, UUID pmPlanId, String planName, UUID assetId, String assetCode,
      String assetName, LocalDate dueOn, String trigger, String status, UUID assigneeUserId, UUID jobCardId,
      Instant startedAt, UUID startedBy, Instant completedAt, UUID completedBy, List<Checklist.Item> checklist,
      List<Checklist.Result> results, int okCount, int failedCount, String remarks, List<UUID> photoMediaIds,
      long costPaise, BigDecimal usageAtDone) {
    public static TaskResponse from(PmTaskService.TaskView v) {
      PmTask t = v.task();
      return new TaskResponse(t.getId(), t.getNumber(), t.getPmPlanId(), v.planName(), t.getAssetId(), v.assetCode(),
          v.assetName(), t.getDueOn(), t.getTrigger().name(), t.getStatus().name(), t.getAssigneeUserId(),
          t.getJobCardId(), t.getStartedAt(), t.getStartedBy(), t.getCompletedAt(), t.getCompletedBy(), v.checklist(),
          v.results(), t.getOkCount(), t.getFailedCount(), t.getRemarks(), List.of(t.getPhotoMediaIds()),
          t.getCostPaise(), t.getUsageAtDone());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('pm:execute', 'pm:manage', 'asset:view')")
  public List<TaskResponse> list(@RequestParam(required = false) String status,
      @RequestParam(required = false) UUID assetId, @RequestParam(required = false) UUID assigneeUserId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer limit) {
    return tasks.search(status, assetId, assigneeUserId, from, to, CursorPage.clampLimit(limit)).stream()
        .map(TaskResponse::from).toList();
  }

  /** The caller's own PM queue (technician "My tasks"). */
  @GetMapping("/mine")
  @PreAuthorize("@perm.has('pm:execute')")
  public List<TaskResponse> mine(@RequestParam(required = false) String status) {
    UUID me = TenantContext.userId().orElseThrow();
    return tasks.search(status, null, me, null, null, CursorPage.MAX_LIMIT).stream()
        .filter(v -> status != null || v.task().isOpen()).map(TaskResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('pm:execute', 'pm:manage', 'asset:view')")
  public TaskResponse get(@PathVariable UUID id) {
    return TaskResponse.from(tasks.get(id));
  }

  @PostMapping("/{id}/start")
  @PreAuthorize("@perm.has('pm:execute')")
  public TaskResponse start(@PathVariable UUID id) {
    return TaskResponse.from(tasks.start(id));
  }

  @PostMapping("/{id}/complete")
  @PreAuthorize("@perm.has('pm:execute')")
  public TaskResponse complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest r) {
    return TaskResponse.from(tasks.complete(id, new PmTaskService.Completion(r.results(), r.remarks(),
        r.photoMediaIds(), r.costPaise(), r.usageReading(), r.raiseBreakdown())));
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("@perm.has('pm:manage')")
  public TaskResponse cancel(@PathVariable UUID id) {
    return TaskResponse.from(tasks.cancel(id));
  }

  @PostMapping("/{id}/assign")
  @PreAuthorize("@perm.has('pm:manage')")
  public TaskResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest r) {
    return TaskResponse.from(tasks.assign(id, r.userId()));
  }

  /**
   * Runs the daily jobs (PM generation, overdue, expiry alerts) now for the active society, for
   * {@code date} (default: today in the society's time zone). Idempotent; the scheduler does the
   * same every night.
   */
  @PostMapping("/generate")
  @PreAuthorize("@perm.has('pm:manage')")
  public GenerateResponse generate(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    LocalDate day = date == null ? reference.today() : date;
    DailyAssetJobs.Result r = jobs.runFor(TenantContext.activeSocietyId(), day);
    return new GenerateResponse(day, r.pmTasksGenerated(), r.pmTasksOverdue(), r.expiryAlerts());
  }
}
