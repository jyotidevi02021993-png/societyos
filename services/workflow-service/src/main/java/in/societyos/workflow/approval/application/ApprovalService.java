package in.societyos.workflow.approval.application;

import in.societyos.workflow.approval.domain.ApprovalDecision;
import in.societyos.workflow.approval.domain.ApprovalEvents;
import in.societyos.workflow.approval.domain.ApprovalTask;
import in.societyos.workflow.approval.domain.WorkflowInstance;
import in.societyos.workflow.approval.infrastructure.ApprovalDecisionRepository;
import in.societyos.workflow.approval.infrastructure.ApprovalTaskRepository;
import in.societyos.workflow.approval.infrastructure.WorkflowInstanceRepository;
import in.societyos.workflow.common.WorkflowNotifications;
import in.societyos.workflow.definition.application.DefinitionService;
import in.societyos.workflow.definition.domain.ApprovalPlan;
import in.societyos.workflow.directory.application.RoleDirectory;
import in.societyos.workflow.platform.core.error.ProblemException;
import in.societyos.workflow.platform.core.tenant.Tenant;
import in.societyos.workflow.platform.core.tenant.TenantContext;
import in.societyos.workflow.platform.events.DomainEvents;
import in.societyos.workflow.scheduling.application.Timers;
import in.societyos.workflow.sla.application.Escalations;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-step approvals. A subject (job card cost, PO, expense) starts an instance under the active
 * definition of its kind; each step opens an {@link ApprovalTask} for a role or a user; approving
 * the last step approves the instance, one rejection rejects it. Undecided tasks escalate through
 * db-scheduler wake-ups.
 */
@Service
public class ApprovalService implements Timers.TimerHandler {

  public static final String TASK = "approval-escalation";

  public record InstanceDetail(WorkflowInstance instance, List<ApprovalTask> tasks,
      Map<UUID, List<ApprovalDecision>> decisions) {}

  private final WorkflowInstanceRepository instances;
  private final ApprovalTaskRepository tasks;
  private final ApprovalDecisionRepository decisions;
  private final DefinitionService definitions;
  private final DomainEvents events;
  private final Timers wakeups;
  private final Escalations escalations;
  private final RoleDirectory roles;
  private final WorkflowNotifications notifications;
  private final Clock clock;

  public ApprovalService(WorkflowInstanceRepository instances, ApprovalTaskRepository tasks,
      ApprovalDecisionRepository decisions, DefinitionService definitions, DomainEvents events, Timers wakeups,
      Escalations escalations, RoleDirectory roles, WorkflowNotifications notifications, Clock clock) {
    this.instances = instances;
    this.tasks = tasks;
    this.decisions = decisions;
    this.definitions = definitions;
    this.events = events;
    this.wakeups = wakeups;
    this.escalations = escalations;
    this.roles = roles;
    this.notifications = notifications;
    this.clock = clock;
  }

  @Override
  public String taskName() {
    return TASK;
  }

  /**
   * Starts the approval of a subject. Idempotent while an instance is running. Without an active
   * definition, or at or below its threshold, the instance is approved at once (and announced, so
   * the owner never waits on a workflow that does not exist).
   */
  @Transactional
  public WorkflowInstance start(String subjectType, UUID subjectId, String subjectRef, long amountPaise) {
    String type = subjectType(subjectType);
    if (subjectId == null) {
      throw ProblemException.badRequest("SUBJECT_REQUIRED", "subjectId is required");
    }
    Optional<WorkflowInstance> running =
        instances.findBySubjectTypeAndSubjectIdAndStatus(type, subjectId, WorkflowInstance.Status.RUNNING);
    if (running.isPresent()) {
      return running.get();
    }
    Instant now = clock.instant();
    Optional<DefinitionService.ActiveDefinition> active = definitions.active(type);
    WorkflowInstance instance = new WorkflowInstance(active.map(a -> a.definition().getId()).orElse(null),
        active.map(a -> a.definition().getDefVersion()).orElse(null), type, subjectId, subjectRef, amountPaise, now);
    if (active.isEmpty() || !active.get().plan().needsApproval(amountPaise)) {
      instance.approve(null, active.isEmpty() ? "No approval workflow configured" : "Within the auto-approval limit",
          now);
      instance = instances.save(instance);
      events.publish(ApprovalEvents.approved(instance));
      return instance;
    }
    instance = instances.saveAndFlush(instance);
    openStep(instance, active.get().plan().stepsFor(amountPaise), 1, now);
    return instances.save(instance);
  }

  /** The subject was withdrawn: cancels its running instance and open tasks (no event). */
  @Transactional
  public WorkflowInstance cancel(UUID instanceId, String comment) {
    WorkflowInstance instance = instance(instanceId);
    instance.cancel(TenantContext.userId().orElse(null), comment, clock.instant());
    tasks.findByInstanceIdOrderByStepAsc(instanceId).forEach(t -> {
      t.cancel();
      tasks.save(t);
    });
    return instances.save(instance);
  }

  /** One approver's decision on a task. */
  @Transactional
  public ApprovalTask decide(UUID taskId, boolean approve, String comment) {
    Tenant me = TenantContext.current();
    UUID user = me.userId();
    ApprovalTask task = tasks.findById(taskId).orElseThrow(() -> ProblemException.notFound("approval_task", taskId));
    if (!task.canBeDecidedBy(user, me.roles())) {
      throw ProblemException.forbidden("NOT_YOUR_APPROVAL", "This approval is assigned to someone else");
    }
    if (decisions.existsByTaskIdAndDecidedBy(taskId, user)) {
      throw ProblemException.conflict("ALREADY_DECIDED", "You have already decided on this approval");
    }
    WorkflowInstance instance = instance(task.getInstanceId());
    Instant now = clock.instant();
    String note = comment == null || comment.isBlank() ? null : comment.trim();
    decisions.save(new ApprovalDecision(taskId, user, approve ? "APPROVE" : "REJECT", note, now));
    if (!approve) {
      task.reject(user, note, now);
      tasks.save(task);
      instance.reject(user, note, now);
      instances.save(instance);
      events.publish(ApprovalEvents.rejected(instance));
      return task;
    }
    if (!task.approve(user, note, now)) {
      return tasks.save(task); // needs more approvals ("2 of 5")
    }
    tasks.save(task);
    List<ApprovalPlan.Step> steps = stepsOf(instance);
    if (task.getStep() < steps.size()) {
      openStep(instance, steps, task.getStep() + 1, now);
      instances.save(instance);
    } else {
      instance.approve(user, note, now);
      instances.save(instance);
      events.publish(ApprovalEvents.approved(instance));
    }
    return task;
  }

  /** Escalation wake-up of a task that may still be undecided. */
  @Override
  @Transactional
  public void wake(UUID taskId) {
    tasks.findById(taskId).ifPresent(task -> {
      Instant now = clock.instant();
      if (!task.escalateIfDue(now)) {
        return;
      }
      tasks.save(task);
      WorkflowInstance instance = instance(task.getInstanceId());
      escalations.escalate(instance.getSubjectType(), instance.getSubjectId(), instance.getSubjectRef(),
          task.getEscalationLevel(), task.getApproverRole(), Escalations.APPROVAL, task.getId(), now);
      events.publish(ApprovalEvents.requested(instance, task));
    });
  }

  @Transactional(readOnly = true)
  public List<ApprovalTask> inbox() {
    Tenant me = TenantContext.current();
    List<String> myRoles = me.roles().isEmpty() ? List.of("-") : List.copyOf(me.roles());
    return tasks.findInbox(me.userId(), myRoles);
  }

  @Transactional(readOnly = true)
  public List<WorkflowInstance> list(String status, String subjectType, UUID subjectId) {
    if (subjectType != null && subjectId != null) {
      return instances.findBySubjectTypeAndSubjectIdOrderByStartedAtDesc(subjectType(subjectType), subjectId);
    }
    if (status != null && !status.isBlank()) {
      try {
        return instances.findByStatusOrderByStartedAtDesc(
            WorkflowInstance.Status.valueOf(status.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw ProblemException.badRequest("INVALID_STATUS", "Unknown status " + status);
      }
    }
    return instances.findAllByOrderByStartedAtDesc();
  }

  @Transactional(readOnly = true)
  public InstanceDetail detail(UUID instanceId) {
    WorkflowInstance instance = instance(instanceId);
    List<ApprovalTask> steps = tasks.findByInstanceIdOrderByStepAsc(instanceId);
    Map<UUID, List<ApprovalDecision>> votes = new java.util.LinkedHashMap<>();
    steps.forEach(t -> votes.put(t.getId(), decisions.findByTaskIdOrderByDecidedAtAsc(t.getId())));
    return new InstanceDetail(instance, steps, votes);
  }

  private void openStep(WorkflowInstance instance, List<ApprovalPlan.Step> steps, int number, Instant now) {
    ApprovalPlan.Step step = steps.get(number - 1);
    instance.atStep(number);
    Instant dueAt = step.escalateAfterMins() == null ? null : now.plus(Duration.ofMinutes(step.escalateAfterMins()));
    ApprovalTask task = tasks.save(new ApprovalTask(instance.getId(), number, step.name(), step.approverRole(),
        step.approverUserId(), step.requiredApprovals(), dueAt, step.escalateToRole()));
    events.publish(ApprovalEvents.requested(instance, task));
    List<UUID> approvers = new ArrayList<>(roles.holdersOf(step.approverRole()));
    if (step.approverUserId() != null) {
      approvers.add(step.approverUserId());
    }
    notifications.send(approvers, "APPROVAL", "approval.requested",
        Map.of("subjectType", instance.getSubjectType(),
            "number", instance.getSubjectRef() == null ? "" : instance.getSubjectRef(),
            "amountPaise", Long.toString(instance.getAmountPaise()), "step", step.name()),
        false, "approval-requested:" + task.getId());
    if (dueAt != null) {
      wakeups.wakeAt(TASK, TenantContext.activeSocietyId(), task.getId(), dueAt);
    }
  }

  private List<ApprovalPlan.Step> stepsOf(WorkflowInstance instance) {
    if (instance.getDefinitionId() == null) {
      return List.of();
    }
    ApprovalPlan plan = definitions.plan(definitions.get(instance.getDefinitionId()));
    return plan.stepsFor(instance.getAmountPaise());
  }

  private WorkflowInstance instance(UUID id) {
    return instances.findById(id).orElseThrow(() -> ProblemException.notFound("workflow_instance", id));
  }

  private static String subjectType(String subjectType) {
    if (subjectType == null || subjectType.isBlank()) {
      throw ProblemException.badRequest("SUBJECT_TYPE_REQUIRED", "subjectType is required");
    }
    return subjectType.trim().toUpperCase(Locale.ROOT);
  }
}
