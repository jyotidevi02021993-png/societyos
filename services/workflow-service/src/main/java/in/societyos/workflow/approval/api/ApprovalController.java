package in.societyos.workflow.approval.api;

import in.societyos.workflow.approval.application.ApprovalService;
import in.societyos.workflow.approval.domain.ApprovalDecision;
import in.societyos.workflow.approval.domain.ApprovalTask;
import in.societyos.workflow.approval.domain.WorkflowInstance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Approval inbox and decisions ({@code approval:decide}); workflow instances ({@code workflow:manage}). */
@RestController
class ApprovalController {

  private final ApprovalService approvals;

  ApprovalController(ApprovalService approvals) {
    this.approvals = approvals;
  }

  record DecisionRequest(@NotBlank @Pattern(regexp = "(?i)APPROVE|REJECT") String decision, String comment) {}

  record StartRequest(@NotBlank String subjectType, @NotNull UUID subjectId, String subjectRef,
      @PositiveOrZero long amountPaise) {}

  record CancelRequest(String comment) {}

  record TaskResponse(UUID id, UUID instanceId, int step, String stepName, String approverRole, UUID approverUserId,
      int requiredApprovals, int approvalsCount, String status, Instant dueAt, int escalationLevel, UUID decidedBy,
      Instant decidedAt, String comment) {
    static TaskResponse from(ApprovalTask t) {
      return new TaskResponse(t.getId(), t.getInstanceId(), t.getStep(), t.getStepName(), t.getApproverRole(),
          t.getApproverUserId(), t.getRequiredApprovals(), t.getApprovalsCount(), t.getStatus().name(), t.getDueAt(),
          t.getEscalationLevel(), t.getDecidedBy(), t.getDecidedAt(), t.getComment());
    }
  }

  record InstanceResponse(UUID id, String subjectType, UUID subjectId, String subjectRef, long amountPaise,
      String status, int currentStep, UUID definitionId, Integer definitionVersion, Instant startedAt,
      Instant endedAt, UUID decidedBy, String comment) {
    static InstanceResponse from(WorkflowInstance i) {
      return new InstanceResponse(i.getId(), i.getSubjectType(), i.getSubjectId(), i.getSubjectRef(),
          i.getAmountPaise(), i.getStatus().name(), i.getCurrentStep(), i.getDefinitionId(), i.getDefinitionVersion(),
          i.getStartedAt(), i.getEndedAt(), i.getDecidedBy(), i.getComment());
    }
  }

  record VoteResponse(UUID decidedBy, String decision, String comment, Instant decidedAt) {
    static VoteResponse from(ApprovalDecision d) {
      return new VoteResponse(d.getDecidedBy(), d.getDecision(), d.getComment(), d.getDecidedAt());
    }
  }

  record StepResponse(TaskResponse task, List<VoteResponse> votes) {}

  record InstanceDetailResponse(InstanceResponse instance, List<StepResponse> steps) {}

  @GetMapping("/v1/approvals/inbox")
  @PreAuthorize("@perm.has('approval:decide')")
  List<TaskResponse> inbox() {
    return approvals.inbox().stream().map(TaskResponse::from).toList();
  }

  @PostMapping("/v1/approvals/{taskId}/decide")
  @PreAuthorize("@perm.has('approval:decide')")
  TaskResponse decide(@PathVariable UUID taskId, @Valid @RequestBody DecisionRequest r) {
    return TaskResponse.from(approvals.decide(taskId, "APPROVE".equalsIgnoreCase(r.decision()), r.comment()));
  }

  @GetMapping("/v1/instances")
  @PreAuthorize("@perm.hasAny('workflow:manage', 'approval:decide')")
  List<InstanceResponse> list(@RequestParam(required = false) String status,
      @RequestParam(required = false) String subjectType, @RequestParam(required = false) UUID subjectId) {
    return approvals.list(status, subjectType, subjectId).stream().map(InstanceResponse::from).toList();
  }

  @GetMapping("/v1/instances/{id}")
  @PreAuthorize("@perm.hasAny('workflow:manage', 'approval:decide')")
  InstanceDetailResponse get(@PathVariable UUID id) {
    ApprovalService.InstanceDetail d = approvals.detail(id);
    return new InstanceDetailResponse(InstanceResponse.from(d.instance()), d.tasks().stream()
        .map(t -> new StepResponse(TaskResponse.from(t),
            d.decisions().getOrDefault(t.getId(), List.of()).stream().map(VoteResponse::from).toList()))
        .toList());
  }

  /** Manual start, for subjects whose owner does not raise an event (normally started by events). */
  @PostMapping("/v1/instances")
  @PreAuthorize("@perm.has('workflow:manage')")
  ResponseEntity<InstanceResponse> start(@Valid @RequestBody StartRequest r) {
    WorkflowInstance i = approvals.start(r.subjectType(), r.subjectId(), r.subjectRef(), r.amountPaise());
    return ResponseEntity.created(URI.create("/v1/instances/" + i.getId())).body(InstanceResponse.from(i));
  }

  @PostMapping("/v1/instances/{id}/cancel")
  @PreAuthorize("@perm.has('workflow:manage')")
  InstanceResponse cancel(@PathVariable UUID id, @RequestBody(required = false) CancelRequest r) {
    return InstanceResponse.from(approvals.cancel(id, r == null ? null : r.comment()));
  }
}
