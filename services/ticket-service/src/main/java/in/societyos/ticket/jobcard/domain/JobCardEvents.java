package in.societyos.ticket.jobcard.domain;

import in.societyos.ticket.common.TicketEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Job card events, exactly as in contracts/events/CATALOGUE.md (ticket section). */
public final class JobCardEvents {

  private JobCardEvents() {}

  public record JobCardCreated(UUID jobCardId, String number, String sourceType, UUID sourceId, UUID assetId,
      String priority) implements TicketEvent {
    @Override public String type() { return "ticket.jobcard.created"; }
    @Override public UUID aggregateId() { return jobCardId; }
  }

  public record JobCardAssigned(UUID jobCardId, String number, UUID assigneeUserId, UUID vendorId, UUID assetId,
      String priority) implements TicketEvent {
    @Override public String type() { return "ticket.jobcard.assigned"; }
    @Override public UUID aggregateId() { return jobCardId; }
  }

  public record JobCardCompleted(UUID jobCardId, String number, long labourCostPaise, long spareCostPaise,
      long totalCostPaise) implements TicketEvent {
    @Override public String type() { return "ticket.jobcard.completed"; }
    @Override public UUID aggregateId() { return jobCardId; }
  }

  public record SpareUsage(UUID spareId, int qty, long unitCostPaise) {}

  public record JobCardClosed(UUID jobCardId, String number, UUID assetId, String sourceType, UUID sourceId,
      long labourCostPaise, List<SpareUsage> spares, String rootCause, Instant closedAt, UUID recoverableFlatId)
      implements TicketEvent {
    @Override public String type() { return "ticket.jobcard.closed"; }
    @Override public UUID aggregateId() { return jobCardId; }
  }

  public static JobCardCreated created(JobCard c) {
    return new JobCardCreated(c.getId(), c.getNumber(), c.getSourceType(), c.getSourceId(), c.getAssetId(),
        c.getPriority());
  }

  public static JobCardAssigned assigned(JobCard c) {
    return new JobCardAssigned(c.getId(), c.getNumber(), c.getAssigneeUserId(), c.getVendorId(), c.getAssetId(),
        c.getPriority());
  }

  public static JobCardCompleted completed(JobCard c) {
    return new JobCardCompleted(c.getId(), c.getNumber(), c.getLabourCostPaise(), c.getSpareCostPaise(),
        c.totalCostPaise());
  }

  public static JobCardClosed closed(JobCard c, List<JobCardSpare> spares) {
    List<SpareUsage> used = spares.stream().filter(s -> s.getStatus() == JobCardSpare.Status.ISSUED)
        .map(s -> new SpareUsage(s.getSpareId(), s.getQty(), s.getUnitCostPaise())).toList();
    return new JobCardClosed(c.getId(), c.getNumber(), c.getAssetId(), c.getSourceType(), c.getSourceId(),
        c.getLabourCostPaise(), used, c.getRootCause(), c.getClosedAt(), c.getRecoverableFlatId());
  }
}
