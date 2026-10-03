package in.societyos.ticket.breakdown.application;

import in.societyos.ticket.attachment.application.TicketAttachments;
import in.societyos.ticket.breakdown.domain.Breakdown;
import in.societyos.ticket.breakdown.domain.BreakdownReported;
import in.societyos.ticket.breakdown.infrastructure.BreakdownRepository;
import in.societyos.ticket.common.Priority;
import in.societyos.ticket.common.Texts;
import in.societyos.ticket.common.TicketProperties;
import in.societyos.ticket.directory.application.Directory;
import in.societyos.ticket.directory.domain.AssetSummary;
import in.societyos.ticket.history.application.TicketHistory;
import in.societyos.ticket.history.domain.TicketHistoryEntry;
import in.societyos.ticket.jobcard.application.JobCardService;
import in.societyos.ticket.jobcard.application.JobCardService.NewJobCard;
import in.societyos.ticket.jobcard.domain.JobCard;
import in.societyos.ticket.platform.core.error.ProblemException;
import in.societyos.ticket.platform.core.tenant.TenantContext;
import in.societyos.ticket.platform.events.DomainEvents;
import in.societyos.ticket.platform.jpa.DocumentNumberService;
import in.societyos.ticket.platform.security.PermissionEvaluator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Breakdowns: every report gets a job card at once; closing the card resolves the breakdown. */
@Service
public class BreakdownService {

  public record ReportBreakdown(UUID assetId, UUID locationId, String fault, String priority, List<UUID> mediaIds,
      UUID assigneeUserId, UUID vendorId) {}

  public record BreakdownDetail(Breakdown breakdown, String assetName, List<UUID> mediaIds, JobCard jobCard,
      List<TicketHistoryEntry> history) {}

  private final BreakdownRepository breakdowns;
  private final JobCardService jobCards;
  private final TicketAttachments attachments;
  private final TicketHistory history;
  private final Directory directory;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final PermissionEvaluator perm;
  private final TicketProperties properties;
  private final Clock clock;

  public BreakdownService(BreakdownRepository breakdowns, JobCardService jobCards, TicketAttachments attachments,
      TicketHistory history, Directory directory, DocumentNumberService numbers, DomainEvents events,
      PermissionEvaluator perm, TicketProperties properties, Clock clock) {
    this.breakdowns = breakdowns;
    this.jobCards = jobCards;
    this.attachments = attachments;
    this.history = history;
    this.directory = directory;
    this.numbers = numbers;
    this.events = events;
    this.perm = perm;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public Breakdown report(ReportBreakdown r) {
    boolean canAssign = perm.has("jobcard:assign");
    if (!canAssign && (r.assigneeUserId() != null || r.vendorId() != null)) {
      throw ProblemException.forbidden("NOT_ALLOWED", "Assigning needs jobcard:assign");
    }
    return create(r, "MANUAL", null);
  }

  /** {@code utility.checklist.item_failed}: an automatic breakdown, once per run and item. */
  @Transactional
  public void checklistItemFailed(UUID runId, String itemCode, String itemLabel, UUID assetId, UUID locationId,
      String note) {
    String ref = "CHECKLIST:" + runId + ":" + itemCode;
    if (breakdowns.existsBySourceRef(ref) || assetId == null && locationId == null) {
      return;
    }
    String fault = "Checklist item failed: " + (itemLabel == null ? itemCode : itemLabel)
        + (note == null || note.isBlank() ? "" : " (" + note.trim() + ")");
    create(new ReportBreakdown(assetId, locationId, fault, "P2", List.of(), null, null), "CHECKLIST", ref);
  }

  private Breakdown create(ReportBreakdown r, String source, String sourceRef) {
    String priority = Priority.of(r.priority(), "P2");
    Instant now = clock.instant();
    Breakdown b = breakdowns.save(new Breakdown(numbers.next("BRK"), r.assetId(), r.locationId(),
        TenantContext.userId().orElse(null), r.fault(), priority, source, sourceRef, now,
        now.plus(Duration.ofMinutes(properties.resolveMins(priority)))));
    attachments.attach(TicketHistory.BREAKDOWN, b.getId(), r.mediaIds());
    history.record(TicketHistory.BREAKDOWN, b.getId(), null, b.getStatus().name(),
        "CHECKLIST".equals(source) ? "Raised from a failed checklist item" : "Reported");
    events.publish(new BreakdownReported(b.getId(), b.getNumber(), b.getAssetId(), b.getPriority()));
    String category = directory.asset(r.assetId()).map(AssetSummary::getName).orElse("Breakdown");
    JobCard card = jobCards.create(new NewJobCard("BREAKDOWN", b.getId(), null, r.locationId(), r.assetId(),
        category, priority, b.getFault(), r.assigneeUserId(), r.vendorId()));
    b.jobCardRaised(card.getId());
    return breakdowns.save(b);
  }

  @Transactional(readOnly = true)
  public List<Breakdown> list(String status) {
    if (status == null || status.isBlank()) {
      return breakdowns.findAllByOrderByReportedAtDesc();
    }
    try {
      return breakdowns.findByStatusOrderByReportedAtDesc(Breakdown.Status.valueOf(status.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_STATUS", "Unknown breakdown status " + status);
    }
  }

  @Transactional(readOnly = true)
  public BreakdownDetail detail(UUID id) {
    Breakdown b = require(id);
    JobCard card = b.getJobCardId() == null ? null : jobCards.find(b.getJobCardId()).orElse(null);
    return new BreakdownDetail(b, directory.asset(b.getAssetId()).map(AssetSummary::getName).orElse(null),
        attachments.of(TicketHistory.BREAKDOWN, id), card, history.of(TicketHistory.BREAKDOWN, id));
  }

  @Transactional
  public void repairStarted(UUID id) {
    breakdowns.findById(id).ifPresent(b -> {
      Breakdown.Status previous = b.repairStarted();
      if (previous != b.getStatus()) {
        breakdowns.save(b);
        history.record(TicketHistory.BREAKDOWN, id, previous.name(), b.getStatus().name(), "Repair started");
      }
    });
  }

  @Transactional
  public void jobCardClosed(UUID id, Instant at) {
    breakdowns.findById(id).ifPresent(b -> {
      Breakdown.Status previous = b.resolved(at == null ? clock.instant() : at);
      if (previous != b.getStatus()) {
        breakdowns.save(b);
        history.record(TicketHistory.BREAKDOWN, id, previous.name(), b.getStatus().name(),
            "Resolved, downtime " + b.getDowntimeMins() + " min");
      }
    });
  }

  @Transactional
  public Optional<Breakdown> markBreached(UUID id) {
    return breakdowns.findById(id).map(b -> {
      b.slaBreached();
      history.record(TicketHistory.BREAKDOWN, id, null, null, "SLA breached");
      return breakdowns.save(b);
    });
  }

  @Transactional
  public Optional<Breakdown> markEscalated(UUID id, int level, String toRole) {
    return breakdowns.findById(id).map(b -> {
      b.escalate(level, toRole);
      history.record(TicketHistory.BREAKDOWN, id, null, null, "Escalated to " + toRole + " (level " + level + ")");
      return breakdowns.save(b);
    });
  }

  private Breakdown require(UUID id) {
    return breakdowns.findById(id).orElseThrow(() -> ProblemException.notFound("breakdown", id));
  }

  static String clean(String s) {
    return Texts.clean(s);
  }
}
