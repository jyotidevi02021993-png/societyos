package in.societyos.ticket.escalation.application;

import in.societyos.ticket.breakdown.application.BreakdownService;
import in.societyos.ticket.complaint.application.ComplaintService;
import in.societyos.ticket.complaint.domain.Complaint;
import in.societyos.ticket.directory.application.Directory;
import in.societyos.ticket.jobcard.application.JobCardService;
import in.societyos.ticket.jobcard.domain.JobCard;
import in.societyos.ticket.notification.application.TicketNotifications;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reacts to workflow-service's SLA timers (docs/architecture/06 §2 "SLA breach"): a warning nudges
 * the assignee; a breach flags the ticket and its job card; each escalation step records the new
 * owner role and alerts the holders of that role, the assignee and the resident.
 */
@Service
public class SlaEscalationService {

  private final ComplaintService complaints;
  private final BreakdownService breakdowns;
  private final JobCardService jobCards;
  private final Directory directory;
  private final TicketNotifications notifications;

  public SlaEscalationService(ComplaintService complaints, BreakdownService breakdowns, JobCardService jobCards,
      Directory directory, TicketNotifications notifications) {
    this.complaints = complaints;
    this.breakdowns = breakdowns;
    this.jobCards = jobCards;
    this.directory = directory;
    this.notifications = notifications;
  }

  private record Target(String number, UUID resident, Optional<JobCard> card) {}

  @Transactional
  public void warning(String subjectType, UUID subjectId, String kind, Instant dueAt) {
    Optional<JobCard> card = cardOf(subjectType, subjectId);
    card.map(JobCard::getAssigneeUserId).ifPresent(assignee -> notifications.send(List.of(assignee),
        TicketNotifications.ALERT, "sla.warning", params(subjectType, card.get().getNumber(), kind, dueAt),
        "sla-warning:" + subjectType + ":" + subjectId + ":" + kind));
  }

  @Transactional
  public void breached(String subjectType, UUID subjectId, String kind, Instant dueAt) {
    Target target = switch (subjectType) {
      case "COMPLAINT" -> complaints.markBreached(subjectId)
          .map(c -> new Target(c.getNumber(), c.getRaisedBy(), cardOf(subjectType, subjectId))).orElse(null);
      case "BREAKDOWN" -> breakdowns.markBreached(subjectId)
          .map(b -> new Target(b.getNumber(), null, cardOf(subjectType, subjectId))).orElse(null);
      case "JOBCARD" -> jobCards.find(subjectId).map(j -> new Target(j.getNumber(), null, Optional.of(j))).orElse(null);
      default -> null;
    };
    if (target == null) {
      return;
    }
    target.card().ifPresent(c -> jobCards.markBreached(c.getId()));
    List<UUID> to = new ArrayList<>();
    target.card().map(JobCard::getAssigneeUserId).ifPresent(to::add);
    notifications.urgent(to, TicketNotifications.ALERT, "sla.breached",
        params(subjectType, target.number(), kind, dueAt), "sla-breached:" + subjectType + ":" + subjectId + ":" + kind);
  }

  @Transactional
  public void escalated(String subjectType, UUID subjectId, int level, String toRole) {
    Target target = switch (subjectType) {
      case "COMPLAINT" -> complaints.markEscalated(subjectId, level, toRole)
          .map(c -> new Target(c.getNumber(), c.getRaisedBy(), cardOf(subjectType, subjectId))).orElse(null);
      case "BREAKDOWN" -> breakdowns.markEscalated(subjectId, level, toRole)
          .map(b -> new Target(b.getNumber(), null, cardOf(subjectType, subjectId))).orElse(null);
      case "JOBCARD" -> jobCards.find(subjectId).map(j -> new Target(j.getNumber(), null, Optional.of(j))).orElse(null);
      default -> null;
    };
    if (target == null) {
      return;
    }
    target.card().ifPresent(c -> jobCards.markEscalated(c.getId(), level, toRole));
    List<UUID> to = new ArrayList<>(directory.holdersOf(toRole));
    target.card().map(JobCard::getAssigneeUserId).ifPresent(to::add);
    if (target.resident() != null) {
      to.add(target.resident());
    }
    Map<String, String> p = params(subjectType, target.number(), null, null);
    p.put("level", Integer.toString(level));
    p.put("toRole", toRole);
    notifications.urgent(to, TicketNotifications.ALERT, "ticket.escalated", p,
        "escalated:" + subjectType + ":" + subjectId + ":" + level);
  }

  private Optional<JobCard> cardOf(String subjectType, UUID subjectId) {
    return switch (subjectType) {
      case "COMPLAINT" -> complaints.detailForSystem(subjectId).map(Complaint::getJobCardId).flatMap(jobCards::find)
          .or(() -> jobCards.openCardFor("COMPLAINT", subjectId));
      case "BREAKDOWN" -> jobCards.openCardFor("BREAKDOWN", subjectId);
      case "JOBCARD" -> jobCards.find(subjectId);
      default -> Optional.empty();
    };
  }

  private static Map<String, String> params(String subjectType, String number, String kind, Instant dueAt) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("subjectType", subjectType);
    p.put("number", number);
    if (kind != null) {
      p.put("kind", kind);
    }
    if (dueAt != null) {
      p.put("dueAt", dueAt.toString());
    }
    return p;
  }
}
