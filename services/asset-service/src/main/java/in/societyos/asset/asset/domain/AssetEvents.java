package in.societyos.asset.asset.domain;

import in.societyos.asset.common.AssetDomainEvent;
import java.util.UUID;

/** Asset events on {@code sos.asset.events.v1} (contracts/events/CATALOGUE.md). */
public final class AssetEvents {
  private AssetEvents() {}

  public record AssetCreated(UUID assetId, String code, String name, String categoryGroup,
      UUID locationId, String status) implements AssetDomainEvent {
    @Override public String type() { return "asset.asset.created"; }
    @Override public UUID aggregateId() { return assetId; }
  }

  public record AssetUpdated(UUID assetId, String code, String name, String categoryGroup,
      UUID locationId, String status) implements AssetDomainEvent {
    @Override public String type() { return "asset.asset.updated"; }
    @Override public UUID aggregateId() { return assetId; }
  }

  public record AssetStatusChanged(UUID assetId, String code, String from, String to) implements AssetDomainEvent {
    @Override public String type() { return "asset.asset.status_changed"; }
    @Override public UUID aggregateId() { return assetId; }
  }

  /**
   * {@code asset.breakdown.reported} (new: not yet in the catalogue). Hands a breakdown found
   * in asset-service (manual report, failed PM check) to ticket-service, which opens the ticket.
   * {@code fault} is staff text about the equipment, never resident data.
   */
  public record BreakdownReported(UUID assetId, String code, String name, UUID locationId, String fault,
      String priority, String source, UUID pmTaskId, UUID reportedBy) implements AssetDomainEvent {
    @Override public String type() { return "asset.breakdown.reported"; }
    @Override public UUID aggregateId() { return assetId; }
  }
}
