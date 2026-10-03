package in.societyos.utility.reference.infrastructure;

import in.societyos.utility.platform.events.CloudEvent;
import in.societyos.utility.platform.events.DomainEventListener;
import in.societyos.utility.reference.application.ReferenceData;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Read models: assets from asset-service, locations from society-service.
 * DLQs: {@code sos.dlq.utility.asset-refs}, {@code sos.dlq.utility.society-refs}.
 */
@Component
class ReferenceEventsListener {

  static final String ASSET_TOPIC = "sos.asset.events.v1";
  static final String ASSET_GROUP = "utility.asset-refs";
  static final String SOCIETY_TOPIC = "sos.society.events.v1";
  static final String SOCIETY_GROUP = "utility.society-refs";

  record AssetChanged(UUID assetId, String code, String name, String categoryGroup, UUID locationId, String status) {}

  record AssetStatusChanged(UUID assetId, String code, String from, String to) {}

  record LocationCreated(UUID locationId, String kind, String name, UUID towerId, UUID parentId) {}

  private final ReferenceData reference;

  ReferenceEventsListener(ReferenceData reference) {
    this.reference = reference;
  }

  @DomainEventListener(topic = ASSET_TOPIC, group = ASSET_GROUP, type = {"asset.asset.created", "asset.asset.updated"})
  void onAsset(CloudEvent<AssetChanged> event) {
    AssetChanged a = event.data();
    if (event.societyId() != null && a.assetId() != null) {
      reference.upsertAsset(a.assetId(), a.code(), a.name(), a.categoryGroup(), a.locationId(), a.status());
    }
  }

  @DomainEventListener(topic = SOCIETY_TOPIC, group = SOCIETY_GROUP, type = "society.location.created")
  void onLocation(CloudEvent<LocationCreated> event) {
    LocationCreated l = event.data();
    if (event.societyId() != null && l.locationId() != null) {
      reference.upsertLocation(l.locationId(), l.kind(), l.name(), l.towerId(), l.parentId());
    }
  }
}
