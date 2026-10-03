package in.societyos.ticket.jobcard.application;

import in.societyos.ticket.common.Priority;
import in.societyos.ticket.common.Texts;
import in.societyos.ticket.common.TicketProperties;
import in.societyos.ticket.directory.application.Directory;
import in.societyos.ticket.history.application.TicketHistory;
import in.societyos.ticket.history.domain.TicketHistoryEntry;
import in.societyos.ticket.jobcard.domain.JobCard;
import in.societyos.ticket.jobcard.domain.JobCardEvents;
import in.societyos.ticket.jobcard.domain.JobCardEvidence;
import in.societyos.ticket.jobcard.domain.JobCardLabour;
import in.societyos.ticket.jobcard.domain.JobCardSpare;
import in.societyos.ticket.jobcard.domain.JobCardStatus;
import in.societyos.ticket.jobcard.infrastructure.JobCardEvidenceRepository;
import in.societyos.ticket.jobcard.infrastructure.JobCardLabourRepository;
import in.societyos.ticket.jobcard.infrastructure.JobCardRepository;
import in.societyos.ticket.jobcard.infrastructure.JobCardSpareRepository;
import in.societyos.ticket.notification.application.TicketNotifications;
import in.societyos.ticket.platform.core.error.ProblemException;
import in.societyos.ticket.platform.core.tenant.TenantContext;
import in.societyos.ticket.platform.events.DomainEvents;
import in.societyos.ticket.platform.jpa.DocumentNumberService;
import in.societyos.ticket.platform.security.PermissionEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Job card use cases: raise, assign, work (log, spares, evidence), complete, verify, close. */
@Service
public class JobCardService {

  private static final Logger log = LoggerFactory.getLogger(JobCardService.class);

  public record NewJobCard(String sourceType, UUID sourceId, UUID flatId, UUID locationId, UUID assetId,
      String categoryName, String priority, String fault, UUID assigneeUserId, UUID vendorId) {}

  public record TransitionCommand(String action, String workDone, String rootCause, String reason, String note,
      UUID recoverableFlatId) {}

  public record JobCardDetail(JobCard card, String flatLabel, List<JobCardLabour> labour, List<JobCardSpare> spares,
      List<JobCardEvidence> evidence, List<TicketHistoryEntry> history) {}

  private final JobCardRepository cards;
  private final JobCardLabourRepository labour;
  private final JobCardSpareRepository spares;
  private final JobCardEvidenceRepository evidence;
  private final ObjectProvider<JobCardSourceHook> hooks;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final TicketHistory history;
  private final TicketNotifications notifications;
  private final Directory directory;
  private final PermissionEvaluator perm;
  private final TicketProperties properties;
  private final Clock clock;

  public JobCardService(JobCardRepository cards, JobCardLabourRepository labour, JobCardSpareRepository spares,
      JobCardEvidenceRepository evidence, ObjectProvider<JobCardSourceHook> hooks, DocumentNumberService numbers,
      DomainEvents events, TicketHistory history, TicketNotifications notifications, Directory directory,
      PermissionEvaluator perm, TicketProperties properties, Clock clock) {
    this.cards = cards;
    this.labour = labour;
    this.spares = spares;
    this.evidence = evidence;
    this.hooks = hooks;
    this.numbers = numbers;
    this.events = events;
    this.history = history;
    this.notifications = notifications;
    this.directory = directory;
    this.perm = perm;
    this.properties = properties;
    this.clock = clock;
  }

  // --- raise and assign -------------------------------------------------------------------

  /** Raises a job card (from any source). Assigns it at once when an assignee or vendor is given. */
  @Transactional
  public JobCard create(NewJobCard c) {
    String sourceType = c.sourceType() == null ? null : c.sourceType().trim().toUpperCase(Locale.ROOT);
    if (c.sourceId() != null && sourceType != null
        && cards.existsBySourceTypeAndSourceIdAndStatusNot(sourceType, c.sourceId(), JobCardStatus.CLOSED)) {
      throw ProblemException.conflict("JOB_CARD_EXISTS", "An open job card already exists for this " + sourceType);
    }
    if (sourceType == null || !JobCard.SOURCE_TYPES.contains(sourceType)) {
      throw ProblemException.badRequest("INVALID_SOURCE_TYPE", "sourceType must be one of " + JobCard.SOURCE_TYPES);
    }
    JobCard card = new JobCard(numbers.next("JC"), sourceType, c.sourceId(), c.flatId(), c.locationId(),
        c.assetId(), Texts.clean(c.categoryName()), Priority.of(c.priority(), "P3"), c.fault());
    card = cards.save(card);
    history.record(TicketHistory.JOBCARD, card.getId(), null, card.getStatus().name(), "Raised from " + sourceType);
    events.publish(JobCardEvents.created(card));
    if (c.assigneeUserId() != null || c.vendorId() != null) {
      assignCard(card, c.assigneeUserId(), c.vendorId());
    }
    return card;
  }

  @Transactional
  public JobCard assign(UUID id, UUID assigneeUserId, UUID vendorId) {
    return assignCard(require(id), assigneeUserId, vendorId);
  }

  private JobCard assignCard(JobCard card, UUID assigneeUserId, UUID vendorId) {
    JobCardStatus previous = card.assign(assigneeUserId, vendorId, clock.instant());
    card = cards.save(card);
    history.record(TicketHistory.JOBCARD, card.getId(), previous.name(), card.getStatus().name(),
        "Assigned to " + (assigneeUserId != null ? "technician" : "vendor"));
    events.publish(JobCardEvents.assigned(card));
    notifications.send(assigneeUserId == null ? List.<UUID>of() : List.of(assigneeUserId),
        TicketNotifications.JOBCARD, "jobcard.assigned",
        params(card), "jobcard-assigned:" + card.getId() + ":" + card.getAssignedAt().toEpochMilli());
    return card;
  }

  // --- reads ----------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<JobCard> list(String status, UUID assigneeUserId) {
    requireViewAll();
    if (assigneeUserId != null) {
      return cards.findByAssigneeUserIdOrderByCreatedAtDesc(assigneeUserId);
    }
    if (status != null && !status.isBlank()) {
      return cards.findByStatusOrderByCreatedAtAsc(status(status));
    }
    return cards.findAllByOrderByCreatedAtDesc();
  }

  /** The technician's (or vendor agent's) queue: cards assigned to them that still need work. */
  @Transactional(readOnly = true)
  public List<JobCard> myTasks() {
    UUID me = TenantContext.userId().orElseThrow(() -> ProblemException.forbidden("NOT_ALLOWED", "No user"));
    return cards.findByAssigneeUserIdAndStatusInOrderByCreatedAtAsc(me, EnumSet.of(JobCardStatus.ASSIGNED,
        JobCardStatus.IN_PROGRESS, JobCardStatus.WAITING, JobCardStatus.REOPENED, JobCardStatus.COMPLETED));
  }

  @Transactional(readOnly = true)
  public JobCardDetail detail(UUID id) {
    JobCard card = require(id);
    if (!canViewAll() && !card.isAssignedTo(TenantContext.userId().orElse(null))) {
      throw ProblemException.notFound("job_card", id);
    }
    return new JobCardDetail(card, directory.flatLabel(card.getFlatId()).orElse(null),
        labour.findByJobCardIdOrderByLoggedAtAsc(id), spares.findByJobCardIdOrderByCreatedAtAsc(id),
        evidence.findByJobCardIdOrderByTakenAtAsc(id), history.of(TicketHistory.JOBCARD, id));
  }

  @Transactional(readOnly = true)
  public Optional<JobCard> find(UUID id) {
    return id == null ? Optional.empty() : cards.findById(id);
  }

  @Transactional(readOnly = true)
  public Optional<JobCard> openCardFor(String sourceType, UUID sourceId) {
    return cards.findFirstBySourceTypeAndSourceIdAndStatusNot(sourceType, sourceId, JobCardStatus.CLOSED);
  }

  // --- transitions ----------------------------------------------------------------------

  @Transactional
  public JobCard transition(UUID id, TransitionCommand c) {
    JobCard card = require(id);
    card.requireUnlocked();
    String action = c.action() == null ? "" : c.action().trim().toUpperCase(Locale.ROOT);
    Instant now = clock.instant();
    UUID me = TenantContext.userId().orElse(null);
    JobCardStatus previous;
    switch (action) {
      case "START" -> {
        requireWorker(card);
        previous = card.start(now);
      }
      case "WAIT" -> {
        requireWorker(card);
        previous = card.waitFor(c.reason() == null ? null : c.reason().trim().toUpperCase(Locale.ROOT));
      }
      case "RESUME" -> {
        requireWorker(card);
        previous = card.resume();
      }
      case "COMPLETE" -> {
        requireWorker(card);
        long labourPaise = labour.findByJobCardIdOrderByLoggedAtAsc(id).stream()
            .mapToLong(JobCardLabour::getCostPaise).sum();
        long sparePaise = spareCost(id);
        previous = card.complete(c.workDone(), c.rootCause(), labourPaise, sparePaise, c.recoverableFlatId(),
            evidence.existsByJobCardIdAndStage(id, "AFTER"), properties.jobcardApprovalThresholdPaise(), now);
      }
      case "REWORK" -> {
        require("jobcard:approve");
        previous = card.rework();
      }
      case "VERIFY" -> {
        require("jobcard:approve");
        previous = card.verify(me, now);
      }
      case "REOPEN" -> {
        require("jobcard:approve");
        previous = card.reopen();
      }
      case "CLOSE" -> {
        require("jobcard:close");
        previous = card.close(me, null, now);
      }
      default -> throw ProblemException.badRequest("INVALID_ACTION",
          "action must be one of START, WAIT, RESUME, COMPLETE, REWORK, VERIFY, REOPEN, CLOSE");
    }
    return afterTransition(card, previous, Texts.clean(c.note()));
  }

  /** The resident accepted the work on a complaint's job card: close it. */
  @Transactional
  public void closeConfirmedByResident(UUID id) {
    JobCard card = require(id);
    if (card.getStatus() != JobCardStatus.VERIFIED) {
      return; // not verified yet (manager resolved the complaint directly): leave the card to the manager
    }
    JobCardStatus previous = card.close(TenantContext.userId().orElse(null), true, clock.instant());
    afterTransition(card, previous, "Resident confirmed the work");
  }

  /** The resident rejected the work: reopen a verified card. */
  @Transactional
  public void reopenRejectedByResident(UUID id) {
    JobCard card = require(id);
    if (card.getStatus() != JobCardStatus.VERIFIED) {
      return;
    }
    JobCardStatus previous = card.reopen();
    afterTransition(card, previous, "Resident rejected the work");
  }

  private JobCard afterTransition(JobCard card, JobCardStatus previous, String note) {
    card = cards.save(card);
    history.record(TicketHistory.JOBCARD, card.getId(), previous.name(), card.getStatus().name(), note);
    JobCard saved = card;
    switch (card.getStatus()) {
      case IN_PROGRESS -> {
        if (previous == JobCardStatus.ASSIGNED || previous == JobCardStatus.REOPENED) {
          hook(saved, h -> h.started(saved));
        }
      }
      case COMPLETED -> events.publish(JobCardEvents.completed(card));
      case VERIFIED -> hook(saved, h -> h.verified(saved));
      case REOPENED -> hook(saved, h -> h.reopened(saved));
      case CLOSED -> {
        events.publish(JobCardEvents.closed(card, spares.findByJobCardIdOrderByCreatedAtAsc(card.getId())));
        hook(saved, h -> h.closed(saved));
      }
      default -> { }
    }
    return card;
  }

  private void hook(JobCard card, Consumer<JobCardSourceHook> call) {
    hooks.orderedStream().filter(h -> h.sourceType().equals(card.getSourceType())).forEach(call);
  }

  // --- work log, spares, evidence ---------------------------------------------------------

  @Transactional
  public JobCardLabour logWork(UUID id, String note, int minutes, long costPaise) {
    JobCard card = require(id);
    card.requireUnlocked();
    requireWorker(card);
    if (card.getStatus() == JobCardStatus.OPEN || card.getStatus() == JobCardStatus.VERIFIED) {
      throw ProblemException.unprocessable("INVALID_TRANSITION", "Work can be logged while the card is being worked");
    }
    JobCardLabour line = labour.save(new JobCardLabour(id, TenantContext.userId().orElse(null), note, minutes,
        costPaise, clock.instant()));
    history.record(TicketHistory.JOBCARD, id, null, null, "Work logged: " + minutes + " min");
    return line;
  }

  @Transactional
  public JobCardSpare requestSpare(UUID id, UUID spareId, UUID storeId, int qty, String note) {
    JobCard card = require(id);
    card.requireUnlocked();
    if (!card.getStatus().isActiveWork()) {
      throw ProblemException.unprocessable("INVALID_TRANSITION", "Spares are requested while the work is open");
    }
    if (!perm.has("jobcard:assign") && !card.isAssignedTo(TenantContext.userId().orElse(null))) {
      throw ProblemException.forbidden("NOT_YOUR_JOB_CARD", "Only the assignee can request spares");
    }
    JobCardSpare line = spares.save(JobCardSpare.requested(id, spareId, storeId, qty, Texts.clean(note),
        TenantContext.userId().orElse(null)));
    history.record(TicketHistory.JOBCARD, id, null, null, "Spare requested: qty " + qty);
    return line;
  }

  @Transactional
  public void cancelSpare(UUID id, UUID lineId) {
    JobCard card = require(id);
    card.requireUnlocked();
    JobCardSpare line = spares.findById(lineId).filter(s -> s.getJobCardId().equals(id))
        .orElseThrow(() -> ProblemException.notFound("spare_request", lineId));
    line.cancel();
    spares.save(line);
  }

  @Transactional(readOnly = true)
  public List<JobCardSpare> spares(UUID id) {
    detail(id);
    return spares.findByJobCardIdOrderByCreatedAtAsc(id);
  }

  @Transactional
  public JobCardEvidence addEvidence(UUID id, String stage, UUID mediaId, Instant takenAt, Double lat, Double lng) {
    JobCard card = require(id);
    card.requireUnlocked();
    requireWorker(card);
    if (card.getStatus() == JobCardStatus.OPEN) {
      throw ProblemException.unprocessable("INVALID_TRANSITION", "Assign the job card before adding evidence");
    }
    if (evidence.existsByJobCardIdAndMediaId(id, mediaId)) {
      throw ProblemException.conflict("EVIDENCE_EXISTS", "This photo is already attached");
    }
    String s = stage == null ? null : stage.trim().toUpperCase(Locale.ROOT);
    return evidence.save(new JobCardEvidence(id, s, mediaId, takenAt == null ? clock.instant() : takenAt, lat, lng,
        TenantContext.userId().orElse(null)));
  }

  // --- from other services ----------------------------------------------------------------

  /** {@code asset.pmtask.due}: one PM job card per task. */
  @Transactional
  public void pmTaskDue(UUID pmTaskId, UUID assetId, String assetName, String dueOn) {
    if (cards.existsBySourceTypeAndSourceIdAndStatusNot("PM", pmTaskId, JobCardStatus.CLOSED)) {
      return;
    }
    create(new NewJobCard("PM", pmTaskId, null, null, assetId, "Preventive maintenance", "P3",
        "Preventive maintenance of " + (assetName == null ? "asset" : assetName) + " due " + dueOn, null, null));
  }

  /** {@code inventory.spare.issued}: confirm the matching request (or record the issue) and update the cost. */
  @Transactional
  public void spareIssued(UUID issueId, UUID jobCardId, UUID spareId, UUID storeId, int qty, long unitCostPaise) {
    if (jobCardId == null || spares.existsByIssueId(issueId)) {
      return;
    }
    Optional<JobCard> found = cards.findById(jobCardId);
    if (found.isEmpty()) {
      log.warn("Spare issue {} for unknown job card {}", issueId, jobCardId);
      return;
    }
    JobCard card = found.get();
    if (card.isLocked()) {
      log.warn("Spare issue {} arrived after job card {} was closed; left for inventory reconciliation", issueId,
          card.getNumber());
      return;
    }
    Instant now = clock.instant();
    spares.findByJobCardIdOrderByCreatedAtAsc(jobCardId).stream()
        .filter(s -> s.getStatus() == JobCardSpare.Status.REQUESTED && s.getSpareId().equals(spareId))
        .findFirst()
        .ifPresentOrElse(s -> {
          s.issued(issueId, storeId, qty, unitCostPaise, now);
          spares.save(s);
        }, () -> spares.save(JobCardSpare.issuedDirectly(jobCardId, spareId, storeId, qty, unitCostPaise, issueId, now)));
    spares.flush();
    card.spareCostChanged(spareCost(jobCardId));
    cards.save(card);
    history.record(TicketHistory.JOBCARD, jobCardId, null, null, "Spare issued: qty " + qty);
  }

  /** workflow-service opened an approval for the card's cost. */
  @Transactional
  public void approvalRequested(UUID jobCardId, UUID instanceId) {
    cards.findById(jobCardId).filter(c -> !c.isLocked()).ifPresent(card -> {
      card.approvalRequested(instanceId);
      cards.save(card);
    });
  }

  @Transactional
  public void approvalDecided(UUID jobCardId, UUID instanceId, boolean approved, String comment) {
    cards.findById(jobCardId).filter(c -> !c.isLocked()).ifPresent(card -> {
      JobCardStatus previous = card.getStatus();
      if (!card.approvalDecided(instanceId, approved)) {
        return;
      }
      cards.save(card);
      history.record(TicketHistory.JOBCARD, card.getId(), previous.name(), card.getStatus().name(),
          (approved ? "Cost approved" : "Cost rejected") + (comment == null ? "" : ": " + comment));
      if (!approved) {
        notifications.send(card.getAssigneeUserId() == null ? List.<UUID>of() : List.of(card.getAssigneeUserId()),
            TicketNotifications.JOBCARD, "jobcard.approval_rejected", params(card),
            "jobcard-approval-rejected:" + card.getId() + ":" + instanceId);
      }
    });
  }

  /** SLA breach or escalation on the card itself or on its source ticket. */
  @Transactional
  public Optional<JobCard> markBreached(UUID jobCardId) {
    return cards.findById(jobCardId).filter(c -> !c.isLocked()).map(card -> {
      card.slaBreached();
      return cards.save(card);
    });
  }

  @Transactional
  public Optional<JobCard> markEscalated(UUID jobCardId, int level, String toRole) {
    return cards.findById(jobCardId).filter(c -> !c.isLocked()).map(card -> {
      card.escalate(level, toRole);
      JobCard saved = cards.save(card);
      history.record(TicketHistory.JOBCARD, card.getId(), null, null, "Escalated to " + toRole + " (level " + level + ")");
      return saved;
    });
  }

  // --- helpers ------------------------------------------------------------------------------

  public static Map<String, String> params(JobCard card) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("number", card.getNumber());
    p.put("priority", card.getPriority());
    p.put("status", card.getStatus().name());
    if (card.getCategoryName() != null) {
      p.put("category", card.getCategoryName());
    }
    p.put("jobCardId", card.getId().toString());
    return p;
  }

  private long spareCost(UUID jobCardId) {
    return spares.findByJobCardIdOrderByCreatedAtAsc(jobCardId).stream().mapToLong(JobCardSpare::costPaise).sum();
  }

  private JobCard require(UUID id) {
    return cards.findById(id).orElseThrow(() -> ProblemException.notFound("job_card", id));
  }

  /** The assignee works on the card; a supervisor with {@code jobcard:assign} may act for them. */
  private void requireWorker(JobCard card) {
    UUID me = TenantContext.userId().orElse(null);
    if (perm.has("jobcard:assign")) {
      return;
    }
    if (!perm.has("jobcard:work") || !card.isAssignedTo(me)) {
      throw ProblemException.forbidden("NOT_YOUR_JOB_CARD", "Only the assignee can work on this job card");
    }
  }

  private void require(String permission) {
    if (!perm.has(permission)) {
      throw ProblemException.forbidden("NOT_ALLOWED", "You need " + permission);
    }
  }

  private boolean canViewAll() {
    return perm.hasAny("jobcard:view", "jobcard:assign", "jobcard:approve", "jobcard:close");
  }

  private void requireViewAll() {
    if (!canViewAll()) {
      throw ProblemException.forbidden("NOT_ALLOWED", "Use /v1/my-tasks for your own job cards");
    }
  }

  private static JobCardStatus status(String value) {
    try {
      return JobCardStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_STATUS", "Unknown job card status " + value);
    }
  }
}
