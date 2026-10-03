package in.societyos.asset.reference.infrastructure;

import in.societyos.asset.platform.events.CloudEvent;
import in.societyos.asset.platform.events.DomainEventListener;
import in.societyos.asset.reference.application.ReferenceData;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Read models from other services: societies (for the scheduler's time zones), locations and
 * vendors. DLQs: {@code sos.dlq.asset.society-refs}, {@code sos.dlq.asset.vendor-refs}.
 */
@Component
class ReferenceEventsListener {

  static final String SOCIETY_TOPIC = "sos.society.events.v1";
  static final String SOCIETY_GROUP = "asset.society-refs";
  static final String VENDOR_TOPIC = "sos.vendor.events.v1";
  static final String VENDOR_GROUP = "asset.vendor-refs";

  record SocietyCreated(UUID societyId, String name, String timezone) {}

  record LocationCreated(UUID locationId, String kind, String name, UUID towerId, UUID parentId) {}

  record VendorChanged(UUID vendorId, String code, String name, String status) {}

  private final ReferenceData reference;

  ReferenceEventsListener(ReferenceData reference) {
    this.reference = reference;
  }

  @DomainEventListener(topic = SOCIETY_TOPIC, group = SOCIETY_GROUP, type = "society.created")
  void onSocietyCreated(CloudEvent<SocietyCreated> event) {
    UUID id = event.data().societyId() != null ? event.data().societyId() : event.societyId();
    if (id != null) {
      reference.registerSociety(id, event.data().name(), event.data().timezone());
    }
  }

  @DomainEventListener(topic = SOCIETY_TOPIC, group = SOCIETY_GROUP, type = "society.location.created")
  void onLocationCreated(CloudEvent<LocationCreated> event) {
    LocationCreated l = event.data();
    if (event.societyId() == null || l.locationId() == null) {
      return;
    }
    reference.upsertLocation(l.locationId(), l.kind(), l.name(), l.towerId(), l.parentId());
  }

  @DomainEventListener(topic = VENDOR_TOPIC, group = VENDOR_GROUP,
      type = {"vendor.vendor.created", "vendor.vendor.updated"})
  void onVendor(CloudEvent<VendorChanged> event) {
    VendorChanged v = event.data();
    if (event.societyId() == null || v.vendorId() == null) {
      return;
    }
    reference.upsertVendor(v.vendorId(), v.code(), v.name(), v.status());
  }
}
