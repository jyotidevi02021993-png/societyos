package in.societyos.ticket.jobcard.infrastructure;

import in.societyos.ticket.jobcard.application.JobCardService;
import in.societyos.ticket.platform.events.CloudEvent;
import in.societyos.ticket.platform.events.DomainEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Work arriving from other services: PM tasks due (asset-service) and spares issued
 * (inventory-service). Groups {@code ticket.pm-jobcards} and {@code ticket.spare-issues}, each with
 * DLQ {@code sos.dlq.<group>}.
 */
@Component
class JobCardIntakeListener {

  record PmTaskDue(UUID pmTaskId, UUID pmPlanId, UUID assetId, String assetCode, String assetName, String dueOn,
      UUID checklistTemplateId) {}

  record SpareIssued(UUID issueId, UUID spareId, UUID storeId, int qty, long unitCostPaise, UUID jobCardId) {}

  private final JobCardService jobCards;

  JobCardIntakeListener(JobCardService jobCards) {
    this.jobCards = jobCards;
  }

  @DomainEventListener(topic = "sos.asset.events.v1", group = "ticket.pm-jobcards", type = "asset.pmtask.due")
  void onPmTaskDue(CloudEvent<PmTaskDue> e) {
    PmTaskDue t = e.data();
    jobCards.pmTaskDue(t.pmTaskId(), t.assetId(), t.assetName() != null ? t.assetName() : t.assetCode(), t.dueOn());
  }

  @DomainEventListener(topic = "sos.inventory.events.v1", group = "ticket.spare-issues",
      type = "inventory.spare.issued")
  void onSpareIssued(CloudEvent<SpareIssued> e) {
    SpareIssued s = e.data();
    jobCards.spareIssued(s.issueId(), s.jobCardId(), s.spareId(), s.storeId(), s.qty(), s.unitCostPaise());
  }
}
