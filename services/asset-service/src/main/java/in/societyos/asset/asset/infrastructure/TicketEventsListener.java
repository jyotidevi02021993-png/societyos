package in.societyos.asset.asset.infrastructure;

import in.societyos.asset.asset.application.AssetService;
import in.societyos.asset.platform.events.CloudEvent;
import in.societyos.asset.platform.events.DomainEventListener;
import in.societyos.asset.pm.application.PmTaskService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Feedback from ticket-service (doc 06 flows 2 and 3): breakdowns set the asset status, PM job
 * cards link to PM tasks, closed job cards write history and cost and complete PM tasks.
 * DLQ: {@code sos.dlq.asset.ticket-feedback}.
 */
@Component
class TicketEventsListener {

  static final String TOPIC = "sos.ticket.events.v1";
  static final String GROUP = "asset.ticket-feedback";

  record BreakdownReported(UUID breakdownId, String number, UUID assetId, String priority) {}

  record JobCardCreated(UUID jobCardId, String number, String sourceType, UUID sourceId, UUID assetId, String priority) {}

  record Spare(UUID spareId, Long qty, Long unitCostPaise) {}

  record JobCardClosed(UUID jobCardId, String number, UUID assetId, String sourceType, UUID sourceId,
      Long labourCostPaise, List<Spare> spares, String rootCause, Instant closedAt) {
    long totalCostPaise() {
      long total = labourCostPaise == null ? 0 : labourCostPaise;
      if (spares != null) {
        for (Spare s : spares) {
          total += (s.qty() == null ? 0 : s.qty()) * (s.unitCostPaise() == null ? 0 : s.unitCostPaise());
        }
      }
      return total;
    }
  }

  private final AssetService assets;
  private final PmTaskService pmTasks;

  TicketEventsListener(AssetService assets, PmTaskService pmTasks) {
    this.assets = assets;
    this.pmTasks = pmTasks;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.breakdown.reported")
  void onBreakdown(CloudEvent<BreakdownReported> event) {
    BreakdownReported b = event.data();
    if (b.assetId() != null) {
      assets.onTicketBreakdown(b.assetId(), b.breakdownId(), b.number(), b.priority());
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.jobcard.created")
  void onJobCardCreated(CloudEvent<JobCardCreated> event) {
    JobCardCreated j = event.data();
    if ("PM".equals(j.sourceType()) && j.sourceId() != null) {
      pmTasks.linkJobCard(j.sourceId(), j.jobCardId());
    } else if (j.assetId() != null) {
      assets.onRepairStarted(j.assetId(), j.jobCardId(), j.number());
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "ticket.jobcard.closed")
  void onJobCardClosed(CloudEvent<JobCardClosed> event) {
    JobCardClosed j = event.data();
    if ("PM".equals(j.sourceType())) {
      pmTasks.completeFromJobCard(j.sourceId(), j.jobCardId(), j.totalCostPaise(), j.closedAt());
    } else if (j.assetId() != null) {
      assets.onRepairClosed(j.assetId(), j.jobCardId(), j.number(), j.totalCostPaise(), j.rootCause(), j.closedAt());
    }
  }
}
