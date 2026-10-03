package in.societyos.workflow.sla.application;

import in.societyos.workflow.common.WorkflowNotifications;
import in.societyos.workflow.directory.application.RoleDirectory;
import in.societyos.workflow.platform.events.DomainEvents;
import in.societyos.workflow.sla.domain.EscalationLog;
import in.societyos.workflow.sla.domain.SlaEvents;
import in.societyos.workflow.sla.infrastructure.EscalationLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an escalation and publishes {@code workflow.escalated}. For approval escalations it also
 * alerts the holders of the new role; SLA escalations are announced by the subject's owner service.
 */
@Service
public class Escalations {

  public static final String SLA = "SLA";
  public static final String APPROVAL = "APPROVAL";

  private final EscalationLogRepository log;
  private final DomainEvents events;
  private final RoleDirectory roles;
  private final WorkflowNotifications notifications;

  public Escalations(EscalationLogRepository log, DomainEvents events, RoleDirectory roles,
      WorkflowNotifications notifications) {
    this.log = log;
    this.events = events;
    this.roles = roles;
    this.notifications = notifications;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void escalate(String subjectType, UUID subjectId, String subjectRef, int level, String toRole, String reason,
      UUID refId, Instant at) {
    log.save(new EscalationLog(subjectType, subjectId, level, toRole, reason, refId, at));
    events.publish(new SlaEvents.Escalated(subjectType, subjectId, level, toRole));
    if (!APPROVAL.equals(reason)) {
      return; // SLA escalations are announced by the subject's owner (ticket-service alerts ticket people)
    }
    notifications.send(roles.holdersOf(toRole), "ALERT", "workflow.escalated",
        Map.of("subjectType", subjectType, "number", subjectRef == null ? "" : subjectRef,
            "level", Integer.toString(level), "toRole", toRole, "reason", reason),
        true, "escalated:" + subjectType + ":" + subjectId + ":" + reason + ":" + level);
  }

  @Transactional(readOnly = true)
  public List<EscalationLog> of(String subjectType, UUID subjectId) {
    return log.findBySubjectTypeAndSubjectIdOrderByAtAsc(subjectType, subjectId);
  }
}
