package in.societyos.ticket.jobcard.api;

import in.societyos.ticket.history.domain.TicketHistoryEntry;
import in.societyos.ticket.jobcard.application.JobCardService;
import in.societyos.ticket.jobcard.application.JobCardService.JobCardDetail;
import in.societyos.ticket.jobcard.application.JobCardService.NewJobCard;
import in.societyos.ticket.jobcard.application.JobCardService.TransitionCommand;
import in.societyos.ticket.jobcard.domain.JobCard;
import in.societyos.ticket.jobcard.domain.JobCardEvidence;
import in.societyos.ticket.jobcard.domain.JobCardLabour;
import in.societyos.ticket.jobcard.domain.JobCardSpare;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job cards. Transition permissions are checked per action in the use case: START/WAIT/RESUME/
 * COMPLETE need {@code jobcard:work} as the assignee (or {@code jobcard:assign}); REWORK/VERIFY/REOPEN
 * need {@code jobcard:approve}; CLOSE needs {@code jobcard:close}.
 */
@RestController
class JobCardController {

  private static final String ANY_JOBCARD =
      "@perm.hasAny('jobcard:view', 'jobcard:work', 'jobcard:assign', 'jobcard:approve', 'jobcard:close')";

  private final JobCardService jobCards;

  JobCardController(JobCardService jobCards) {
    this.jobCards = jobCards;
  }

  record JobCardRequest(@NotBlank String sourceType, UUID sourceId, UUID flatId, UUID locationId, UUID assetId,
      String category, String priority, @NotBlank String fault, UUID assigneeUserId, UUID vendorId) {}

  record AssignmentRequest(UUID assigneeUserId, UUID vendorId) {}

  record TransitionRequest(@NotBlank String action, String workDone, String rootCause, String reason, String note,
      UUID recoverableFlatId) {}

  record WorkLogRequest(@NotBlank String note, @PositiveOrZero int minutes, @PositiveOrZero long costPaise) {}

  record SpareRequest(@NotNull UUID spareId, UUID storeId, @Positive int qty, String note) {}

  record EvidenceRequest(@NotBlank String stage, @NotNull UUID mediaId, Instant takenAt, Double lat, Double lng) {}

  record JobCardResponse(UUID id, String number, String sourceType, UUID sourceId, UUID flatId, UUID locationId,
      UUID assetId, String category, String priority, String fault, String status, UUID assigneeUserId,
      UUID vendorId, String waitingReason, String workDone, String rootCause, long labourCostPaise,
      long spareCostPaise, long totalCostPaise, UUID recoverableFlatId, String approvalStatus,
      Boolean residentConfirmed, boolean slaBreached, int escalationLevel, String escalatedToRole,
      int reopenedCount, boolean locked, Instant createdAt, Instant startedAt, Instant completedAt,
      Instant verifiedAt, Instant closedAt) {
    static JobCardResponse from(JobCard c) {
      return new JobCardResponse(c.getId(), c.getNumber(), c.getSourceType(), c.getSourceId(), c.getFlatId(),
          c.getLocationId(), c.getAssetId(), c.getCategoryName(), c.getPriority(), c.getFault(),
          c.getStatus().name(), c.getAssigneeUserId(), c.getVendorId(), c.getWaitingReason(), c.getWorkDone(),
          c.getRootCause(), c.getLabourCostPaise(), c.getSpareCostPaise(), c.totalCostPaise(),
          c.getRecoverableFlatId(), c.getApprovalStatus().name(), c.getResidentConfirmed(), c.isSlaBreached(),
          c.getEscalationLevel(), c.getEscalatedToRole(), c.getReopenedCount(), c.isLocked(), c.getCreatedAt(),
          c.getStartedAt(), c.getCompletedAt(), c.getVerifiedAt(), c.getClosedAt());
    }
  }

  record WorkLogResponse(UUID id, UUID workerUserId, String note, int minutes, long costPaise, Instant loggedAt) {
    static WorkLogResponse from(JobCardLabour l) {
      return new WorkLogResponse(l.getId(), l.getWorkerUserId(), l.getNote(), l.getMinutes(), l.getCostPaise(),
          l.getLoggedAt());
    }
  }

  record SpareResponse(UUID id, UUID spareId, UUID storeId, int qty, long unitCostPaise, String status,
      String note, UUID issueId, Instant issuedAt) {
    static SpareResponse from(JobCardSpare s) {
      return new SpareResponse(s.getId(), s.getSpareId(), s.getStoreId(), s.getQty(), s.getUnitCostPaise(),
          s.getStatus().name(), s.getNote(), s.getIssueId(), s.getIssuedAt());
    }
  }

  record EvidenceResponse(UUID id, String stage, UUID mediaId, Instant takenAt, Double lat, Double lng, UUID takenBy) {
    static EvidenceResponse from(JobCardEvidence e) {
      return new EvidenceResponse(e.getId(), e.getStage(), e.getMediaId(), e.getTakenAt(), e.getLat(), e.getLng(),
          e.getTakenBy());
    }
  }

  record HistoryResponse(Instant at, UUID actorId, String fromStatus, String toStatus, String note) {
    static HistoryResponse from(TicketHistoryEntry h) {
      return new HistoryResponse(h.getAt(), h.getActorId(), h.getFromStatus(), h.getToStatus(), h.getNote());
    }
  }

  record JobCardDetailResponse(JobCardResponse jobCard, String flatLabel, List<WorkLogResponse> workLog,
      List<SpareResponse> spares, List<EvidenceResponse> evidence, List<HistoryResponse> history) {
    static JobCardDetailResponse from(JobCardDetail d) {
      return new JobCardDetailResponse(JobCardResponse.from(d.card()), d.flatLabel(),
          d.labour().stream().map(WorkLogResponse::from).toList(),
          d.spares().stream().map(SpareResponse::from).toList(),
          d.evidence().stream().map(EvidenceResponse::from).toList(),
          d.history().stream().map(HistoryResponse::from).toList());
    }
  }

  @PostMapping("/v1/jobcards")
  @PreAuthorize("@perm.has('jobcard:assign')")
  ResponseEntity<JobCardResponse> create(@Valid @RequestBody JobCardRequest r) {
    JobCard card = jobCards.create(new NewJobCard(r.sourceType(), r.sourceId(), r.flatId(), r.locationId(),
        r.assetId(), r.category(), r.priority(), r.fault(), r.assigneeUserId(), r.vendorId()));
    return ResponseEntity.created(URI.create("/v1/jobcards/" + card.getId())).body(JobCardResponse.from(card));
  }

  @GetMapping("/v1/jobcards")
  @PreAuthorize(ANY_JOBCARD)
  List<JobCardResponse> list(@RequestParam(required = false) String status,
      @RequestParam(required = false) UUID assigneeUserId) {
    return jobCards.list(status, assigneeUserId).stream().map(JobCardResponse::from).toList();
  }

  @GetMapping("/v1/my-tasks")
  @PreAuthorize("@perm.hasAny('jobcard:work', 'jobcard:assign')")
  List<JobCardResponse> myTasks() {
    return jobCards.myTasks().stream().map(JobCardResponse::from).toList();
  }

  @GetMapping("/v1/jobcards/{id}")
  @PreAuthorize(ANY_JOBCARD)
  JobCardDetailResponse get(@PathVariable UUID id) {
    return JobCardDetailResponse.from(jobCards.detail(id));
  }

  @PutMapping("/v1/jobcards/{id}/assignment")
  @PreAuthorize("@perm.has('jobcard:assign')")
  JobCardResponse assign(@PathVariable UUID id, @RequestBody AssignmentRequest r) {
    return JobCardResponse.from(jobCards.assign(id, r.assigneeUserId(), r.vendorId()));
  }

  @PostMapping("/v1/jobcards/{id}/transitions")
  @PreAuthorize(ANY_JOBCARD)
  JobCardResponse transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest r) {
    return JobCardResponse.from(jobCards.transition(id, new TransitionCommand(r.action(), r.workDone(),
        r.rootCause(), r.reason(), r.note(), r.recoverableFlatId())));
  }

  @PostMapping("/v1/jobcards/{id}/work-logs")
  @PreAuthorize("@perm.hasAny('jobcard:work', 'jobcard:assign')")
  @ResponseStatus(HttpStatus.CREATED)
  WorkLogResponse logWork(@PathVariable UUID id, @Valid @RequestBody WorkLogRequest r) {
    return WorkLogResponse.from(jobCards.logWork(id, r.note(), r.minutes(), r.costPaise()));
  }

  @GetMapping("/v1/jobcards/{id}/spares")
  @PreAuthorize(ANY_JOBCARD)
  List<SpareResponse> spares(@PathVariable UUID id) {
    return jobCards.spares(id).stream().map(SpareResponse::from).toList();
  }

  @PostMapping("/v1/jobcards/{id}/spares")
  @PreAuthorize("@perm.hasAny('spare:issue-request', 'jobcard:assign')")
  @ResponseStatus(HttpStatus.CREATED)
  SpareResponse requestSpare(@PathVariable UUID id, @Valid @RequestBody SpareRequest r) {
    return SpareResponse.from(jobCards.requestSpare(id, r.spareId(), r.storeId(), r.qty(), r.note()));
  }

  @DeleteMapping("/v1/jobcards/{id}/spares/{lineId}")
  @PreAuthorize("@perm.hasAny('spare:issue-request', 'jobcard:assign')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void cancelSpare(@PathVariable UUID id, @PathVariable UUID lineId) {
    jobCards.cancelSpare(id, lineId);
  }

  @PostMapping("/v1/jobcards/{id}/evidence")
  @PreAuthorize("@perm.hasAny('jobcard:work', 'jobcard:assign')")
  @ResponseStatus(HttpStatus.CREATED)
  EvidenceResponse addEvidence(@PathVariable UUID id, @Valid @RequestBody EvidenceRequest r) {
    return EvidenceResponse.from(jobCards.addEvidence(id, r.stage(), r.mediaId(), r.takenAt(), r.lat(), r.lng()));
  }
}
