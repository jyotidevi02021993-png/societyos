package in.societyos.workflow.sla.api;

import in.societyos.workflow.sla.application.Escalations;
import in.societyos.workflow.sla.application.SlaPolicyService;
import in.societyos.workflow.sla.application.SlaPolicyService.PolicyCommand;
import in.societyos.workflow.sla.application.SlaService;
import in.societyos.workflow.sla.domain.EscalationLog;
import in.societyos.workflow.sla.domain.EscalationStep;
import in.societyos.workflow.sla.domain.SlaPolicy;
import in.societyos.workflow.sla.domain.SlaTimer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SLA policies (workflow:manage) and a subject's timers and escalations. */
@RestController
class SlaController {

  private final SlaPolicyService policies;
  private final SlaService sla;
  private final Escalations escalations;

  SlaController(SlaPolicyService policies, SlaService sla, Escalations escalations) {
    this.policies = policies;
    this.sla = sla;
    this.escalations = escalations;
  }

  record PolicyRequest(@NotBlank String subjectType, String category, String priority, @Positive Integer respondMins,
      @NotNull @Positive Integer resolveMins, Integer warnPercent, List<EscalationStep> escalationChain,
      Boolean active) {
    PolicyCommand command() {
      return new PolicyCommand(subjectType, category, priority, respondMins, resolveMins, warnPercent,
          escalationChain, active);
    }
  }

  record PolicyResponse(UUID id, String subjectType, String category, String priority, Integer respondMins,
      int resolveMins, int warnPercent, List<EscalationStep> escalationChain, boolean active) {}

  record TimerResponse(UUID id, String subjectType, UUID subjectId, String kind, String status, Instant startedAt,
      Instant warnAt, Instant dueAt, Instant warnedAt, Instant firedAt, Instant stoppedAt, int level,
      Instant nextEscalationAt) {
    static TimerResponse from(SlaTimer t) {
      return new TimerResponse(t.getId(), t.getSubjectType(), t.getSubjectId(), t.getKind().name(),
          t.getStatus().name(), t.getStartedAt(), t.getWarnAt(), t.getDueAt(), t.getWarnedAt(), t.getFiredAt(),
          t.getStoppedAt(), t.getLevel(), t.getNextEscalationAt());
    }
  }

  record EscalationResponse(int level, String toRole, String reason, Instant at) {
    static EscalationResponse from(EscalationLog e) {
      return new EscalationResponse(e.getLevel(), e.getToRole(), e.getReason(), e.getAt());
    }
  }

  record SubjectSlaResponse(List<TimerResponse> timers, List<EscalationResponse> escalations) {}

  private PolicyResponse view(SlaPolicy p) {
    return new PolicyResponse(p.getId(), p.getSubjectType(), p.getCategoryName(), p.getPriority(), p.getRespondMins(),
        p.getResolveMins(), p.getWarnPercent(), policies.chain(p), p.isActive());
  }

  @GetMapping("/v1/sla-policies")
  @PreAuthorize("@perm.has('workflow:manage')")
  List<PolicyResponse> list() {
    return policies.list().stream().map(this::view).toList();
  }

  @PostMapping("/v1/sla-policies")
  @PreAuthorize("@perm.has('workflow:manage')")
  ResponseEntity<PolicyResponse> create(@Valid @RequestBody PolicyRequest r) {
    SlaPolicy p = policies.create(r.command());
    return ResponseEntity.created(URI.create("/v1/sla-policies/" + p.getId())).body(view(p));
  }

  @PutMapping("/v1/sla-policies/{id}")
  @PreAuthorize("@perm.has('workflow:manage')")
  PolicyResponse update(@PathVariable UUID id, @Valid @RequestBody PolicyRequest r) {
    return view(policies.update(id, r.command()));
  }

  @GetMapping("/v1/sla-timers")
  @PreAuthorize("@perm.hasAny('workflow:manage', 'approval:decide')")
  SubjectSlaResponse timers(@RequestParam String subjectType, @RequestParam UUID subjectId) {
    String type = subjectType.trim().toUpperCase();
    return new SubjectSlaResponse(sla.timersOf(type, subjectId).stream().map(TimerResponse::from).toList(),
        escalations.of(type, subjectId).stream().map(EscalationResponse::from).toList());
  }

  @GetMapping("/v1/sla-timers/active")
  @PreAuthorize("@perm.has('workflow:manage')")
  List<TimerResponse> active() {
    return sla.active().stream().map(TimerResponse::from).toList();
  }
}
