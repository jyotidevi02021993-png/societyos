package in.societyos.ticket.directory.infrastructure;

import in.societyos.ticket.directory.application.Directory;
import in.societyos.ticket.platform.events.CloudEvent;
import in.societyos.ticket.platform.events.DomainEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Feeds the read models from other contexts. Consumer groups (each with DLQ {@code sos.dlq.<group>}):
 * {@code ticket.flat-directory}, {@code ticket.asset-directory}, {@code ticket.role-directory}.
 */
@Component
class DirectoryEventsListener {

  static final String SOCIETY_TOPIC = "sos.society.events.v1";
  static final String ASSET_TOPIC = "sos.asset.events.v1";
  static final String IDENTITY_TOPIC = "sos.identity.events.v1";
  static final String FLAT_GROUP = "ticket.flat-directory";
  static final String ASSET_GROUP = "ticket.asset-directory";
  static final String ROLE_GROUP = "ticket.role-directory";

  record FlatData(UUID flatId, String towerName, String label, String status) {}
  record LocationData(UUID locationId, String kind, String name) {}
  record MembershipData(UUID membershipId, UUID flatId, UUID userId, String kind) {}
  record AssetData(UUID assetId, String code, String name, UUID locationId, String status) {}
  record AssetStatusData(UUID assetId, String code, String from, String to) {}
  record RoleData(UUID assignmentId, UUID userId, String roleCode) {}

  private final Directory directory;

  DirectoryEventsListener(Directory directory) {
    this.directory = directory;
  }

  @DomainEventListener(topic = SOCIETY_TOPIC, group = FLAT_GROUP,
      type = {"society.flat.created", "society.flat.updated"})
  void onFlat(CloudEvent<FlatData> e) {
    FlatData f = e.data();
    directory.upsertFlat(f.flatId(), f.label(), f.towerName(), f.status());
  }

  @DomainEventListener(topic = SOCIETY_TOPIC, group = FLAT_GROUP, type = "society.location.created")
  void onLocation(CloudEvent<LocationData> e) {
    directory.upsertLocation(e.data().locationId(), e.data().kind(), e.data().name());
  }

  @DomainEventListener(topic = SOCIETY_TOPIC, group = FLAT_GROUP, type = "society.membership.created")
  void onMembershipCreated(CloudEvent<MembershipData> e) {
    MembershipData m = e.data();
    directory.membershipCreated(m.membershipId(), m.flatId(), m.userId(), m.kind());
  }

  @DomainEventListener(topic = SOCIETY_TOPIC, group = FLAT_GROUP, type = "society.membership.ended")
  void onMembershipEnded(CloudEvent<MembershipData> e) {
    directory.membershipEnded(e.data().membershipId());
  }

  @DomainEventListener(topic = ASSET_TOPIC, group = ASSET_GROUP,
      type = {"asset.asset.created", "asset.asset.updated"})
  void onAsset(CloudEvent<AssetData> e) {
    AssetData a = e.data();
    directory.upsertAsset(a.assetId(), a.code(), a.name(), a.locationId(), a.status());
  }

  @DomainEventListener(topic = ASSET_TOPIC, group = ASSET_GROUP, type = "asset.asset.status_changed")
  void onAssetStatus(CloudEvent<AssetStatusData> e) {
    directory.assetStatusChanged(e.data().assetId(), e.data().code(), e.data().to());
  }

  @DomainEventListener(topic = IDENTITY_TOPIC, group = ROLE_GROUP, type = "identity.role.assigned")
  void onRoleAssigned(CloudEvent<RoleData> e) {
    if (e.societyId() != null) {
      directory.roleAssigned(e.data().assignmentId(), e.data().userId(), e.data().roleCode());
    }
  }

  @DomainEventListener(topic = IDENTITY_TOPIC, group = ROLE_GROUP, type = "identity.role.revoked")
  void onRoleRevoked(CloudEvent<RoleData> e) {
    if (e.societyId() != null) {
      directory.roleRevoked(e.data().assignmentId());
    }
  }
}
