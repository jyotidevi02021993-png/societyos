package in.societyos.ticket.breakdown.infrastructure;

import in.societyos.ticket.breakdown.application.BreakdownService;
import in.societyos.ticket.platform.events.CloudEvent;
import in.societyos.ticket.platform.events.DomainEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Failed checklist items (utility-service) raise breakdowns. Group {@code ticket.checklist-failures}. */
@Component
class ChecklistEventsListener {

  record ItemFailed(UUID runId, String itemCode, String itemLabel, UUID assetId, UUID locationId, String note) {}

  private final BreakdownService breakdowns;

  ChecklistEventsListener(BreakdownService breakdowns) {
    this.breakdowns = breakdowns;
  }

  @DomainEventListener(topic = "sos.utility.events.v1", group = "ticket.checklist-failures",
      type = "utility.checklist.item_failed")
  void onItemFailed(CloudEvent<ItemFailed> e) {
    ItemFailed i = e.data();
    breakdowns.checklistItemFailed(i.runId(), i.itemCode(), i.itemLabel(), i.assetId(), i.locationId(), i.note());
  }
}
