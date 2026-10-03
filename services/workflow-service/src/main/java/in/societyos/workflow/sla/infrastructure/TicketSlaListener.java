package in.societyos.workflow.sla.infrastructure;

import in.societyos.workflow.sla.application.SlaService;
import in.societyos.workflow.sla.domain.SlaTimer;
import in.societyos.workflow.platform.events.CloudEvent;
import in.societyos.workflow.platform.events.DomainEventListener;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SLA start/stop from ticket-service (catalogue: timers start on {@code ticket.complaint.created},
 * stop on {@code ticket.complaint.resolved}). Also: RESPOND stops when a job card is raised for the
 * ticket, a reopened complaint restarts its RESOLVE clock, and breakdowns are timed until their job
 * card closes. Group {@code workflow.ticket-sla} (DLQ {@code sos.dlq.workflow.ticket-sla}).
 */
@Component
class TicketSlaListener {

  static final String TOPIC = "sos.ticket.events.v1";
  static final String GROUP = "workflow.ticket-sla";

  record ComplaintCreated(UUID complaintId, String number, String categoryName, String priority) {}
  record ComplaintChanged(UUID complaintId, String number, Instant at) {}
  record BreakdownReported(UUID breakdownId, String number, UUID assetId, String priority) {}
  record JobCardCreated(UUID jobCardId, String number, String sourceType, UUID sourceId) {}
  record JobCardClosed(UUID jobCardId, String number, String sourceType, UUID sourceId, Instant closedAt) {}

  private final SlaService sla;

  TicketSlaListener(SlaService sla) {
    this.sla = sla;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.complaint.created")
  void onComplaintCreated(CloudEvent<ComplaintCreated> e) {
    ComplaintCreated c = e.data();
    sla.start("COMPLAINT", c.complaintId(), c.number(), c.categoryName(), c.priority(), e.time());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.complaint.resolved")
  void onComplaintResolved(CloudEvent<ComplaintChanged> e) {
    sla.stop("COMPLAINT", e.data().complaintId(), null, e.data().at());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.complaint.reopened")
  void onComplaintReopened(CloudEvent<ComplaintChanged> e) {
    sla.restart("COMPLAINT", e.data().complaintId(), e.data().at());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.breakdown.reported")
  void onBreakdownReported(CloudEvent<BreakdownReported> e) {
    BreakdownReported b = e.data();
    sla.start("BREAKDOWN", b.breakdownId(), b.number(), null, b.priority(), e.time());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.jobcard.created")
  void onJobCardCreated(CloudEvent<JobCardCreated> e) {
    JobCardCreated j = e.data();
    if (j.sourceId() != null && ("COMPLAINT".equals(j.sourceType()) || "BREAKDOWN".equals(j.sourceType()))) {
      sla.stop(j.sourceType(), j.sourceId(), SlaTimer.Kind.RESPOND, e.time());
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.jobcard.closed")
  void onJobCardClosed(CloudEvent<JobCardClosed> e) {
    JobCardClosed j = e.data();
    if (j.sourceId() != null && "BREAKDOWN".equals(j.sourceType())) {
      sla.stop("BREAKDOWN", j.sourceId(), null, j.closedAt());
    }
  }
}
