package in.societyos.inventory.stock.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import in.societyos.inventory.common.InventoryEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Inventory events, exactly as in contracts/events/CATALOGUE.md (inventory section). */
public final class StockEvents {

  private StockEvents() {}

  /** ticket-service adds the cost to the job card; {@code issueId} is the ledger row id. */
  public record SpareIssued(UUID issueId, UUID spareId, UUID storeId, int qty, long unitCostPaise, UUID jobCardId)
      implements InventoryEvent {
    @Override public String type() { return "inventory.spare.issued"; }
    @Override public UUID aggregateId() { return issueId; }
  }

  public record StockLow(UUID spareId, String spareName, UUID storeId, int qty, int reorderLevel)
      implements InventoryEvent {
    @Override public String type() { return "inventory.stock.low"; }
    @Override public UUID aggregateId() { return spareId; }
    @Override public String subject() { return "stock/" + storeId + "/" + spareId; }
  }

  /** {@code inventory.notification.requested} (catalogue: {@code <context>.notification.requested}). */
  public record NotificationRequested(@JsonIgnore UUID requestId, List<UUID> recipientUserIds, String category,
      String template, Map<String, String> params, List<String> channels, String priority, String dedupeKey)
      implements InventoryEvent {
    @Override public String type() { return "inventory.notification.requested"; }
    @Override public UUID aggregateId() { return requestId; }
    @Override public String subject() { return "notification/" + requestId; }
  }

  public static SpareIssued issued(StockMovement m) {
    return new SpareIssued(m.getId(), m.getItemId(), m.getStoreId(), -m.getQtyDelta(), m.getUnitCostPaise(),
        m.getJobCardId());
  }
}
