package in.societyos.ticket.complaint.application;

import in.societyos.ticket.attachment.application.TicketAttachments;
import in.societyos.ticket.category.application.CategoryService;
import in.societyos.ticket.category.domain.TicketCategory;
import in.societyos.ticket.common.Priority;
import in.societyos.ticket.common.Texts;
import in.societyos.ticket.common.TicketProperties;
import in.societyos.ticket.complaint.domain.Complaint;
import in.societyos.ticket.complaint.domain.ComplaintEvents;
import in.societyos.ticket.complaint.domain.ComplaintStatus;
import in.societyos.ticket.complaint.infrastructure.ComplaintRepository;
import in.societyos.ticket.directory.application.Directory;
import in.societyos.ticket.history.application.TicketHistory;
import in.societyos.ticket.history.domain.TicketHistoryEntry;
import in.societyos.ticket.jobcard.application.JobCardService;
import in.societyos.ticket.jobcard.application.JobCardService.NewJobCard;
import in.societyos.ticket.jobcard.domain.JobCard;
import in.societyos.ticket.jobcard.domain.JobCardStatus;
import in.societyos.ticket.notification.application.TicketNotifications;
import in.societyos.ticket.platform.core.error.ProblemException;
import in.societyos.ticket.platform.core.tenant.TenantContext;
import in.societyos.ticket.platform.events.DomainEvents;
import in.societyos.ticket.platform.jpa.DocumentNumberService;
import in.societyos.ticket.platform.security.PermissionEvaluator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Complaint use cases: raise (resident), triage into a job card, resolve, feedback, reopen. */
@Service
public class ComplaintService {

  public record RaiseComplaint(UUID flatId, UUID locationId, UUID assetId, UUID categoryId, String category,
      String text, String priority, List<UUID> mediaIds) {}

  public record ComplaintDetail(Complaint complaint, String flatLabel, List<UUID> mediaIds, JobCard jobCard,
      List<TicketHistoryEntry> history) {}

  private final ComplaintRepository complaints;
  private final CategoryService categories;
  private final JobCardService jobCards;
  private final TicketAttachments attachments;
  private final TicketHistory history;
  private final TicketNotifications notifications;
  private final Directory directory;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final PermissionEvaluator perm;
  private final TicketProperties properties;
  private final Clock clock;

  public ComplaintService(ComplaintRepository complaints, CategoryService categories, JobCardService jobCards,
      TicketAttachments attachments, TicketHistory history, TicketNotifications notifications, Directory directory,
      DocumentNumberService numbers, DomainEvents events, PermissionEvaluator perm, TicketProperties properties,
      Clock clock) {
    this.complaints = complaints;
    this.categories = categories;
    this.jobCards = jobCards;
    this.attachments = attachments;
    this.history = history;
    this.notifications = notifications;
    this.directory = directory;
    this.numbers = numbers;
    this.events = events;
    this.perm = perm;
    this.properties = properties;
    this.clock = clock;
  }

  // --- raise ------------------------------------------------------------------------------

  @Transactional
  public Complaint raise(RaiseComplaint r) {
    UUID me = TenantContext.userId().orElse(null);
    UUID flatId = r.flatId();
    if (!isManager()) {
      List<UUID> myFlats = me == null ? List.of() : directory.flatsOf(me);
      if (flatId == null && r.locationId() == null && r.assetId() == null && myFlats.size() == 1) {
        flatId = myFlats.getFirst();
      }
      if (flatId != null && !myFlats.contains(flatId)) {
        throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can raise complaints for your own flat only");
      }
    }
    Optional<TicketCategory> category = r.categoryId() != null ? categories.find(r.categoryId())
        : categories.findByName(r.category());
    if (r.categoryId() != null && category.isEmpty()) {
      throw ProblemException.notFound("category", r.categoryId());
    }
    String priority = Priority.of(r.priority(), category.map(TicketCategory::getDefaultPriority).orElse("P3"));
    Instant now = clock.instant();
    int resolveMins = category.map(TicketCategory::getResolveMins).orElse(null) != null
        ? category.get().getResolveMins() : properties.resolveMins(priority);
    String categoryName = category.map(TicketCategory::getName).orElse(Texts.clean(r.category()));

    Complaint complaint = complaints.save(new Complaint(numbers.next("CMP"), flatId, r.locationId(), r.assetId(), me,
        category.map(TicketCategory::getId).orElse(null), categoryName, priority, r.text(),
        now.plus(Duration.ofMinutes(resolveMins))));
    attachments.attach(TicketHistory.COMPLAINT, complaint.getId(), r.mediaIds());
    history.record(TicketHistory.COMPLAINT, complaint.getId(), null, ComplaintStatus.OPEN.name(), "Raised");
    events.publish(ComplaintEvents.created(complaint));
    notifications.send(me == null ? List.of() : List.of(me), TicketNotifications.COMPLAINT, "complaint.received",
        params(complaint), "complaint-received:" + complaint.getId());

    if (category.isPresent() && category.get().routesAutomatically()) {
      TicketCategory c = category.get();
      raiseCard(complaint, c.getDefaultAssigneeUserId(), c.getDefaultVendorId(), priority, "Routed by category");
    }
    return complaint;
  }

  // --- reads ------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<Complaint> list(String status) {
    if (canViewAll()) {
      return status == null || status.isBlank() ? complaints.findAllByOrderByCreatedAtDesc()
          : complaints.findByStatusOrderByCreatedAtDesc(status(status));
    }
    UUID me = TenantContext.userId().orElseThrow(() -> ProblemException.forbidden("NOT_ALLOWED", "No user"));
    List<UUID> flats = new ArrayList<>(directory.flatsOf(me));
    if (flats.isEmpty()) {
      flats.add(new UUID(0, 0)); // "in ()" is not valid SQL
    }
    List<Complaint> mine = complaints.findVisibleTo(me, flats);
    return status == null || status.isBlank() ? mine
        : mine.stream().filter(c -> c.getStatus() == status(status)).toList();
  }

  @Transactional(readOnly = true)
  public ComplaintDetail detail(UUID id) {
    Complaint c = requireVisible(id);
    JobCard card = c.getJobCardId() == null ? null : jobCards.find(c.getJobCardId()).orElse(null);
    return new ComplaintDetail(c, directory.flatLabel(c.getFlatId()).orElse(null),
        attachments.of(TicketHistory.COMPLAINT, id), card, history.of(TicketHistory.COMPLAINT, id));
  }

  /** No access check: for event handlers running as the system. */
  @Transactional(readOnly = true)
  public Optional<Complaint> detailForSystem(UUID id) {
    return complaints.findById(id);
  }

  // --- manager ----------------------------------------------------------------------------

  /** Triage: turn the complaint into a job card, optionally assigned straight away. */
  @Transactional
  public JobCard raiseJobCard(UUID id, UUID assigneeUserId, UUID vendorId, String priority) {
    Complaint c = require(id);
    if (!c.isOpenForWork()) {
      throw ProblemException.unprocessable("INVALID_TRANSITION",
          "A job card is raised for an OPEN or REOPENED complaint, this one is " + c.getStatus());
    }
    return raiseCard(c, assigneeUserId, vendorId, Priority.of(priority, c.getPriority()), "Job card raised");
  }

  @Transactional
  public Complaint resolve(UUID id, String note) {
    Complaint c = require(id);
    ComplaintStatus previous = c.resolve(clock.instant());
    return resolved(c, previous, Texts.clean(note) == null ? "Resolved by the manager" : note.trim());
  }

  @Transactional
  public Complaint reject(UUID id, String reason) {
    Complaint c = require(id);
    ComplaintStatus previous = c.reject(reason);
    c = complaints.save(c);
    history.record(TicketHistory.COMPLAINT, id, previous.name(), c.getStatus().name(), reason.trim());
    notifications.send(recipients(c.getRaisedBy()), TicketNotifications.COMPLAINT, "complaint.rejected", params(c),
        "complaint-rejected:" + id);
    return c;
  }

  // --- resident ---------------------------------------------------------------------------

  @Transactional
  public Complaint cancel(UUID id) {
    Complaint c = require(id);
    requireRaiserOrManager(c);
    ComplaintStatus previous = c.cancel();
    c = complaints.save(c);
    history.record(TicketHistory.COMPLAINT, id, previous.name(), c.getStatus().name(), "Withdrawn");
    return c;
  }

  /** Accept (close + rate) or reject (reopen) the resolution. */
  @Transactional
  public Complaint feedback(UUID id, boolean accepted, Integer rating, String comment) {
    Complaint c = require(id);
    requireRaiser(c);
    Instant now = clock.instant();
    ComplaintStatus previous = c.feedback(accepted, rating, comment, now);
    c = complaints.save(c);
    history.record(TicketHistory.COMPLAINT, id, previous.name(), c.getStatus().name(),
        accepted ? "Resident accepted, rating " + rating : "Resident rejected the resolution");
    if (c.getJobCardId() != null) {
      if (accepted) {
        jobCards.closeConfirmedByResident(c.getJobCardId());
      } else {
        jobCards.reopenRejectedByResident(c.getJobCardId());
      }
    }
    if (!accepted) {
      events.publish(ComplaintEvents.reopened(c, now));
      notifyAssignee(c, "complaint.reopened");
    }
    return c;
  }

  /** Reopen a resolved or recently closed complaint; a locked job card is replaced by a new one. */
  @Transactional
  public Complaint reopen(UUID id, String reason) {
    Complaint c = require(id);
    requireRaiserOrManager(c);
    Instant now = clock.instant();
    ComplaintStatus previous = c.reopen(now, Duration.ofDays(properties.reopenWindowDays()));
    c = complaints.save(c);
    history.record(TicketHistory.COMPLAINT, id, previous.name(), c.getStatus().name(),
        Texts.clean(reason) == null ? "Reopened" : "Reopened: " + reason.trim());
    events.publish(ComplaintEvents.reopened(c, now));
    Optional<JobCard> card = c.getJobCardId() == null ? Optional.empty() : jobCards.find(c.getJobCardId());
    if (card.isPresent() && card.get().getStatus() == JobCardStatus.VERIFIED) {
      jobCards.reopenRejectedByResident(card.get().getId());
      notifyAssignee(c, "complaint.reopened");
    } else if (card.isPresent() && card.get().getStatus() == JobCardStatus.CLOSED) {
      JobCard old = card.get();
      c.detachJobCard();
      raiseCard(c, old.getAssigneeUserId(), old.getVendorId(), c.getPriority(), "Reopened after closing");
    }
    return c;
  }

  // --- from job cards and SLA events -------------------------------------------------------

  /** Job card verified: the complaint is resolved and the resident is asked for feedback. */
  @Transactional
  public void jobCardVerified(UUID complaintId, UUID cardId) {
    complaints.findById(complaintId).filter(c -> cardId.equals(c.getJobCardId())).ifPresent(c -> {
      if (c.getStatus().canMoveTo(ComplaintStatus.RESOLVED)) {
        ComplaintStatus previous = c.resolve(clock.instant());
        resolved(c, previous, "Work verified");
      }
    });
  }

  @Transactional
  public void jobCardStarted(UUID complaintId, UUID cardId) {
    complaints.findById(complaintId).filter(c -> cardId.equals(c.getJobCardId())).ifPresent(c -> {
      if (c.getStatus() == ComplaintStatus.REOPENED) {
        ComplaintStatus previous = c.jobCardRaised(cardId);
        complaints.save(c);
        history.record(TicketHistory.COMPLAINT, c.getId(), previous.name(), c.getStatus().name(), "Work restarted");
      }
    });
  }

  @Transactional
  public void jobCardReopened(UUID complaintId, UUID cardId) {
    complaints.findById(complaintId).filter(c -> cardId.equals(c.getJobCardId())).ifPresent(c -> {
      if (c.getStatus() == ComplaintStatus.RESOLVED) {
        Instant now = clock.instant();
        ComplaintStatus previous = c.reopen(now, Duration.ofDays(properties.reopenWindowDays()));
        complaints.save(c);
        history.record(TicketHistory.COMPLAINT, c.getId(), previous.name(), c.getStatus().name(), "Work reopened");
        events.publish(ComplaintEvents.reopened(c, now));
      }
    });
  }

  @Transactional
  public void jobCardClosed(UUID complaintId, UUID cardId) {
    complaints.findById(complaintId).filter(c -> cardId.equals(c.getJobCardId())).ifPresent(c -> {
      if (c.getStatus() == ComplaintStatus.RESOLVED) {
        ComplaintStatus previous = c.close(clock.instant());
        complaints.save(c);
        history.record(TicketHistory.COMPLAINT, c.getId(), previous.name(), c.getStatus().name(), "Job card closed");
      }
    });
  }

  @Transactional
  public Optional<Complaint> markBreached(UUID id) {
    return complaints.findById(id).map(c -> {
      c.slaBreached();
      history.record(TicketHistory.COMPLAINT, id, null, null, "SLA breached");
      return complaints.save(c);
    });
  }

  @Transactional
  public Optional<Complaint> markEscalated(UUID id, int level, String toRole) {
    return complaints.findById(id).map(c -> {
      c.escalate(level, toRole);
      history.record(TicketHistory.COMPLAINT, id, null, null, "Escalated to " + toRole + " (level " + level + ")");
      return complaints.save(c);
    });
  }

  // --- helpers ------------------------------------------------------------------------------

  private JobCard raiseCard(Complaint c, UUID assigneeUserId, UUID vendorId, String priority, String note) {
    JobCard card = jobCards.create(new NewJobCard("COMPLAINT", c.getId(), c.getFlatId(), c.getLocationId(),
        c.getAssetId(), c.getCategoryName(), priority, c.getDescription(), assigneeUserId, vendorId));
    ComplaintStatus previous = c.jobCardRaised(card.getId());
    complaints.save(c);
    history.record(TicketHistory.COMPLAINT, c.getId(), previous.name(), c.getStatus().name(),
        note + ": " + card.getNumber());
    return card;
  }

  private Complaint resolved(Complaint c, ComplaintStatus previous, String note) {
    c = complaints.save(c);
    history.record(TicketHistory.COMPLAINT, c.getId(), previous.name(), c.getStatus().name(), note);
    events.publish(ComplaintEvents.resolved(c, c.getResolvedAt()));
    notifications.send(recipients(c.getRaisedBy()), TicketNotifications.COMPLAINT, "complaint.resolved", params(c),
        "complaint-resolved:" + c.getId() + ":" + c.getResolvedAt().toEpochMilli());
    return c;
  }

  private void notifyAssignee(Complaint c, String template) {
    if (c.getJobCardId() == null) {
      return;
    }
    jobCards.find(c.getJobCardId()).map(JobCard::getAssigneeUserId).ifPresent(u -> notifications.send(List.of(u),
        TicketNotifications.JOBCARD, template, params(c), template + ":" + c.getId() + ":" + c.getReopenedCount()));
  }

  public static Map<String, String> params(Complaint c) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("number", c.getNumber());
    p.put("status", c.getStatus().name());
    p.put("priority", c.getPriority());
    if (c.getCategoryName() != null) {
      p.put("category", c.getCategoryName());
    }
    p.put("complaintId", c.getId().toString());
    return p;
  }

  private static List<UUID> recipients(UUID user) {
    return user == null ? List.of() : List.of(user);
  }

  private Complaint require(UUID id) {
    return complaints.findById(id).orElseThrow(() -> ProblemException.notFound("complaint", id));
  }

  private Complaint requireVisible(UUID id) {
    Complaint c = require(id);
    if (canViewAll()) {
      return c;
    }
    UUID me = TenantContext.userId().orElse(null);
    boolean mine = me != null && (me.equals(c.getRaisedBy())
        || c.getFlatId() != null && directory.flatsOf(me).contains(c.getFlatId()));
    if (!mine) {
      throw ProblemException.notFound("complaint", id);
    }
    return c;
  }

  private void requireRaiser(Complaint c) {
    UUID me = TenantContext.userId().orElse(null);
    if (me == null || !me.equals(c.getRaisedBy())) {
      throw ProblemException.forbidden("NOT_YOUR_COMPLAINT", "Only the resident who raised it can do this");
    }
  }

  private void requireRaiserOrManager(Complaint c) {
    if (!isManager()) {
      requireRaiser(c);
    }
  }

  private boolean isManager() {
    return perm.has("complaint:manage");
  }

  private boolean canViewAll() {
    return perm.hasAny("complaint:view", "complaint:manage");
  }

  private static ComplaintStatus status(String value) {
    try {
      return ComplaintStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_STATUS", "Unknown complaint status " + value);
    }
  }
}
