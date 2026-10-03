package in.societyos.asset.pm.domain;

import in.societyos.asset.common.AssetDomainEvent;
import java.time.LocalDate;
import java.util.UUID;

/** PM events on {@code sos.asset.events.v1}. */
public final class PmEvents {

  private PmEvents() {}

  /** {@code asset.pmtask.due}: ticket-service creates a PM job card from it. */
  public record PmTaskDue(UUID pmTaskId, UUID pmPlanId, UUID assetId, String assetCode, String assetName,
      LocalDate dueOn, UUID checklistTemplateId, String number, String trigger, UUID assigneeUserId)
      implements AssetDomainEvent {
    @Override public String type() { return "asset.pmtask.due"; }
    @Override public UUID aggregateId() { return pmTaskId; }
  }

  /** {@code asset.pmtask.overdue}. */
  public record PmTaskOverdue(UUID pmTaskId, UUID pmPlanId, UUID assetId, String assetCode, String assetName,
      LocalDate dueOn, UUID checklistTemplateId, String number) implements AssetDomainEvent {
    @Override public String type() { return "asset.pmtask.overdue"; }
    @Override public UUID aggregateId() { return pmTaskId; }
  }

  /** {@code asset.pmtask.completed} (new: not yet in the catalogue). */
  public record PmTaskCompleted(UUID pmTaskId, UUID pmPlanId, UUID assetId, String number, LocalDate dueOn,
      int okCount, int failedCount, long costPaise, UUID completedBy) implements AssetDomainEvent {
    @Override public String type() { return "asset.pmtask.completed"; }
    @Override public UUID aggregateId() { return pmTaskId; }
  }
}
