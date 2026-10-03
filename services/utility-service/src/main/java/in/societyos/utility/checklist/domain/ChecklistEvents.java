package in.societyos.utility.checklist.domain;

import in.societyos.utility.common.UtilityDomainEvent;
import java.util.UUID;

public final class ChecklistEvents {

  private ChecklistEvents() {}

  /** {@code utility.checklist.completed}. */
  public record ChecklistCompleted(UUID runId, UUID templateId, String templateName, UUID assetId, UUID locationId,
      int okCount, int failedCount) implements UtilityDomainEvent {
    @Override public String type() { return "utility.checklist.completed"; }
    @Override public UUID aggregateId() { return runId; }
  }

  /** {@code utility.checklist.item_failed}: ticket-service raises an auto-ticket from it. */
  public record ChecklistItemFailed(UUID runId, String itemCode, String itemLabel, UUID assetId, UUID locationId,
      String note) implements UtilityDomainEvent {
    @Override public String type() { return "utility.checklist.item_failed"; }
    @Override public UUID aggregateId() { return runId; }
  }
}
