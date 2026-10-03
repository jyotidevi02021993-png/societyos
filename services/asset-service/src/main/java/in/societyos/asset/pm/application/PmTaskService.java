package in.societyos.asset.pm.application;

import in.societyos.asset.alerts.application.AlertService;
import in.societyos.asset.asset.application.AssetHistoryService;
import in.societyos.asset.asset.application.AssetService;
import in.societyos.asset.asset.domain.Asset;
import in.societyos.asset.asset.domain.AssetHistory;
import in.societyos.asset.asset.infrastructure.AssetRepository;
import in.societyos.asset.platform.core.error.ProblemException;
import in.societyos.asset.platform.core.tenant.TenantContext;
import in.societyos.asset.platform.events.DomainEvents;
import in.societyos.asset.platform.jpa.DocumentNumberService;
import in.societyos.asset.pm.domain.Checklist;
import in.societyos.asset.pm.domain.PmEvents;
import in.societyos.asset.pm.domain.PmPlan;
import in.societyos.asset.pm.domain.PmTask;
import in.societyos.asset.pm.infrastructure.PmPlanRepository;
import in.societyos.asset.pm.infrastructure.PmTaskRepository;
import in.societyos.asset.reference.application.ReferenceData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** PM tasks: generation (calendar and usage), execution with checklist results, overdue alerts. */
@Service
public class PmTaskService {

  private static final Logger log = LoggerFactory.getLogger(PmTaskService.class);
  private static final TypeReference<List<Checklist.Result>> RESULTS = new TypeReference<>() {};

  /** Checklist outcome submitted by the technician. */
  public record Completion(List<Checklist.Result> results, String remarks, List<UUID> photoMediaIds,
      Long costPaise, BigDecimal usageReading, Boolean raiseBreakdown) {}

  public record TaskView(PmTask task, String planName, String assetCode, String assetName,
      List<Checklist.Item> checklist, List<Checklist.Result> results) {}

  private final PmTaskRepository tasks;
  private final PmPlanRepository plans;
  private final PmPlanService planService;
  private final AssetRepository assets;
  private final AssetService assetService;
  private final AssetHistoryService history;
  private final AlertService alerts;
  private final ReferenceData reference;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final JsonMapper json;
  private final Clock clock;

  public PmTaskService(PmTaskRepository tasks, PmPlanRepository plans, PmPlanService planService,
      AssetRepository assets, AssetService assetService, AssetHistoryService history, AlertService alerts,
      ReferenceData reference, DocumentNumberService numbers, DomainEvents events, JsonMapper json, Clock clock) {
    this.tasks = tasks;
    this.plans = plans;
    this.planService = planService;
    this.assets = assets;
    this.assetService = assetService;
    this.history = history;
    this.alerts = alerts;
    this.reference = reference;
    this.numbers = numbers;
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  // --- generation -------------------------------------------------------------------------

  /** Creates the calendar tasks that are due by {@code today} in the active society. Idempotent. */
  @Transactional
  public List<PmTask> generateCalendarTasks(LocalDate today) {
    List<PmTask> created = new ArrayList<>();
    for (PmPlan plan : plans.lockGeneratable(today)) {
      Asset asset = assets.findById(plan.getAssetId()).orElse(null);
      if (asset == null || asset.status() == Asset.Status.DISPOSED || asset.status() == Asset.Status.RETIRED) {
        plan.deactivate();
        continue;
      }
      LocalDate due = plan.takeOccurrence(today);
      if (due == null) {
        continue;
      }
      missOpenTasks(plan);
      created.add(createTask(plan, asset, due, PmTask.Trigger.CALENDAR));
    }
    return created;
  }

  /** Usage-based PM: a reading for (asset, metric) may make a plan due. */
  @Transactional
  public List<PmTask> onUsageReading(UUID assetId, String metric, BigDecimal value) {
    if (assetId == null || metric == null || value == null) {
      return List.of();
    }
    List<PmTask> created = new ArrayList<>();
    for (PmPlan plan : plans.findByAssetIdAndUsageMetricAndActiveTrue(assetId, metric.trim().toUpperCase(Locale.ROOT))) {
      boolean due = plan.recordUsage(value);
      if (due && !tasks.existsByPmPlanIdAndStatusIn(plan.getId(), PmTask.OPEN)) {
        Asset asset = assets.findById(assetId).orElse(null);
        if (asset != null && asset.status() != Asset.Status.DISPOSED) {
          created.add(createTask(plan, asset, reference.today(), PmTask.Trigger.USAGE));
        }
      }
    }
    return created;
  }

  /** An extra occurrence raised by a manager (e.g. before an inspection). */
  @Transactional
  public TaskView createManual(UUID planId, LocalDate dueOn) {
    PmPlan plan = planService.require(planId);
    Asset asset = assets.findById(plan.getAssetId()).orElseThrow(() -> ProblemException.notFound("asset", plan.getAssetId()));
    if (!plan.isActive()) {
      throw ProblemException.unprocessable("PM_PLAN_INACTIVE", "The plan is not active");
    }
    return view(createTask(plan, asset, dueOn == null ? reference.today() : dueOn, PmTask.Trigger.MANUAL));
  }

  private PmTask createTask(PmPlan plan, Asset asset, LocalDate due, PmTask.Trigger trigger) {
    PmTask task = tasks.save(new PmTask(numbers.next("PM"), plan, due, trigger));
    events.publish(new PmEvents.PmTaskDue(task.getId(), plan.getId(), asset.getId(), asset.getAssetCode(),
        asset.getName(), due, plan.getChecklistTemplateId(), task.getNumber(), trigger.name(),
        task.getAssigneeUserId()));
    alerts.notify("asset.pm.due", Map.of("taskNumber", task.getNumber(), "assetCode", asset.getAssetCode(),
        "assetName", asset.getName(), "planName", plan.getName(), "dueOn", due.toString()),
        "NORMAL", "pm-due:" + task.getId(), task.getAssigneeUserId());
    log.debug("PM task {} for plan {} due {}", task.getNumber(), plan.getId(), due);
    return task;
  }

  private void missOpenTasks(PmPlan plan) {
    for (PmTask old : tasks.findByPmPlanIdAndStatusIn(plan.getId(), PmTask.OPEN)) {
      old.miss();
      history.record(old.getAssetId(), AssetHistory.Kind.PM_MISSED, "PM_TASK", old.getId(),
          "PM " + old.getNumber() + " due " + old.getDueOn() + " was not done", 0, null);
    }
  }

  // --- overdue ----------------------------------------------------------------------------

  /** Marks open tasks past their due date and alerts once per task. */
  @Transactional
  public int markOverdue(LocalDate today) {
    int count = 0;
    for (PmTask task : tasks.findNewlyOverdue(PmTask.OPEN, today)) {
      if (!task.markOverdue()) {
        continue;
      }
      count++;
      PmPlan plan = plans.findById(task.getPmPlanId()).orElse(null);
      Asset asset = assets.findById(task.getAssetId()).orElse(null);
      String code = asset == null ? "" : asset.getAssetCode();
      String name = asset == null ? "" : asset.getName();
      events.publish(new PmEvents.PmTaskOverdue(task.getId(), task.getPmPlanId(), task.getAssetId(), code, name,
          task.getDueOn(), plan == null ? null : plan.getChecklistTemplateId(), task.getNumber()));
      alerts.notify("asset.pm.overdue", Map.of("taskNumber", task.getNumber(), "assetCode", code,
          "assetName", name, "dueOn", task.getDueOn().toString()), "HIGH", "pm-overdue:" + task.getId(),
          task.getAssigneeUserId());
    }
    return count;
  }

  // --- execution --------------------------------------------------------------------------

  @Transactional
  public TaskView start(UUID id) {
    PmTask task = require(id);
    try {
      task.start(TenantContext.userId().orElse(null), clock.instant());
    } catch (IllegalStateException e) {
      throw ProblemException.unprocessable("PM_TASK_CLOSED", e.getMessage());
    }
    return view(tasks.save(task));
  }

  @Transactional
  public TaskView complete(UUID id, Completion in) {
    PmTask task = require(id);
    if (!task.isOpen()) {
      throw ProblemException.unprocessable("PM_TASK_CLOSED", "PM task " + task.getNumber() + " is " + task.getStatus());
    }
    PmPlan plan = planService.require(task.getPmPlanId());
    Checklist.Evaluation eval;
    try {
      eval = Checklist.evaluate(planService.checklist(plan), in.results());
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_CHECKLIST_RESULT", e.getMessage());
    }
    long cost = in.costPaise() == null ? 0 : in.costPaise();
    if (cost < 0) {
      throw ProblemException.badRequest("INVALID_COST", "costPaise cannot be negative");
    }
    BigDecimal usage = in.usageReading() != null ? in.usageReading() : plan.getLatestUsage();
    Instant now = clock.instant();
    UUID user = TenantContext.userId().orElse(null);
    task.complete(user, now, json.writeValueAsString(eval.results()), eval.ok(), eval.failed(), clean(in.remarks()),
        in.photoMediaIds() == null ? null : in.photoMediaIds().toArray(UUID[]::new), cost, usage);
    plan.taskDone(usage);
    tasks.save(task);
    finish(task, plan, now);
    if (eval.failed() > 0 && Boolean.TRUE.equals(in.raiseBreakdown())) {
      String fault = eval.failures().stream().map(r -> r.label() + ": " + r.note()).collect(Collectors.joining("; "));
      assetService.reportBreakdown(task.getAssetId(), "PM " + task.getNumber() + " failed checks: " + fault, "P2",
          "PM_CHECK", task.getId());
    }
    return view(task);
  }

  @Transactional
  public TaskView cancel(UUID id) {
    PmTask task = require(id);
    try {
      task.cancel();
    } catch (IllegalStateException e) {
      throw ProblemException.unprocessable("PM_TASK_CLOSED", e.getMessage());
    }
    return view(tasks.save(task));
  }

  @Transactional
  public TaskView assign(UUID id, UUID userId) {
    PmTask task = require(id);
    try {
      task.assign(userId);
    } catch (IllegalStateException e) {
      throw ProblemException.unprocessable("PM_TASK_CLOSED", e.getMessage());
    }
    return view(tasks.save(task));
  }

  // --- feedback from ticket-service -------------------------------------------------------

  /** {@code ticket.jobcard.created} with source PM: remember the job card on the task. */
  @Transactional
  public void linkJobCard(UUID pmTaskId, UUID jobCardId) {
    tasks.findById(pmTaskId).ifPresent(t -> t.linkJobCard(jobCardId));
  }

  /** {@code ticket.jobcard.closed} with source PM: the task is done through the job card. */
  @Transactional
  public boolean completeFromJobCard(UUID pmTaskId, UUID jobCardId, long costPaise, Instant at) {
    PmTask task = pmTaskId == null ? null : tasks.findById(pmTaskId).orElse(null);
    if (task == null && jobCardId != null) {
      task = tasks.findByJobCardId(jobCardId).orElse(null);
    }
    if (task == null || !task.isOpen()) {
      return false;
    }
    PmPlan plan = planService.require(task.getPmPlanId());
    task.completeFromJobCard(jobCardId, at == null ? clock.instant() : at, costPaise);
    plan.taskDone(plan.getLatestUsage());
    finish(task, plan, task.getCompletedAt());
    return true;
  }

  private void finish(PmTask task, PmPlan plan, Instant at) {
    history.record(task.getAssetId(), AssetHistory.Kind.PM_DONE, "PM_TASK", task.getId(),
        "PM " + task.getNumber() + " (" + plan.getName() + ") done: " + task.getOkCount() + " OK, "
            + task.getFailedCount() + " not OK", task.getCostPaise(), at);
    assets.findById(task.getAssetId()).ifPresent(a -> a.addMaintenanceCost(task.getCostPaise()));
    events.publish(new PmEvents.PmTaskCompleted(task.getId(), task.getPmPlanId(), task.getAssetId(),
        task.getNumber(), task.getDueOn(), task.getOkCount(), task.getFailedCount(), task.getCostPaise(),
        task.getCompletedBy()));
  }

  // --- queries ----------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public TaskView get(UUID id) {
    return view(require(id));
  }

  @Transactional(readOnly = true)
  public List<TaskView> search(String status, UUID assetId, UUID assignee, LocalDate from, LocalDate to, int limit) {
    PmTask.Status s;
    try {
      s = status == null || status.isBlank() ? null : PmTask.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_STATUS", "status must be one of " + List.of(PmTask.Status.values()));
    }
    LocalDate f = from == null ? LocalDate.of(2000, 1, 1) : from;
    LocalDate t = to == null ? LocalDate.of(2999, 12, 31) : to;
    return tasks.search(s, assetId, assignee, f, t, PageRequest.of(0, limit)).stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  public List<PmTask> openForAsset(UUID assetId) {
    return tasks.findByAssetIdAndStatusInOrderByDueOnAsc(assetId, PmTask.OPEN);
  }

  @Transactional(readOnly = true)
  public List<PmTask> changedSince(Instant since, int limit) {
    return tasks.findByUpdatedAtAfterOrderByUpdatedAtAsc(since, PageRequest.of(0, limit));
  }

  private PmTask require(UUID id) {
    return tasks.findById(id).orElseThrow(() -> ProblemException.notFound("pm_task", id));
  }

  private TaskView view(PmTask t) {
    PmPlan plan = plans.findById(t.getPmPlanId()).orElse(null);
    Asset asset = assets.findById(t.getAssetId()).orElse(null);
    List<Checklist.Result> results = json.readValue(t.getResultsJson(), RESULTS);
    return new TaskView(t, plan == null ? null : plan.getName(), asset == null ? null : asset.getAssetCode(),
        asset == null ? null : asset.getName(), plan == null ? List.of() : planService.checklist(plan),
        results == null ? List.of() : results);
  }

  private static String clean(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
