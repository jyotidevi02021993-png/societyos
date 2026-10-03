package in.societyos.community.directory.infrastructure;

import in.societyos.community.directory.application.DirectoryService;
import in.societyos.community.directory.domain.FacilityRef;
import in.societyos.community.platform.events.CloudEvent;
import in.societyos.community.platform.events.DomainEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Group {@code community.society-directory} on {@code sos.society.events.v1} (DLQ
 * {@code sos.dlq.community.society-directory}): keeps facilities, flats and memberships current.
 */
@Component
class SocietyEventsListener {

  static final String TOPIC = "sos.society.events.v1";
  static final String GROUP = "community.society-directory";

  record Rules(Integer slotMinutes, Integer maxAdvanceDays, Integer maxPerFlatPerWeek) {}

  record Facility(UUID facilityId, String kind, String name, Integer capacity, Boolean chargeable, Long chargePaise,
      Rules bookingRules, String status) {}

  record Flat(UUID flatId, UUID towerId, String label) {}

  record Membership(UUID membershipId, UUID flatId, UUID userId, String kind) {}

  private final DirectoryService directory;

  SocietyEventsListener(DirectoryService directory) {
    this.directory = directory;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = {"society.facility.created", "society.facility.updated"})
  void onFacility(CloudEvent<Facility> e) {
    Facility f = e.data();
    Rules r = f.bookingRules() == null ? new Rules(null, null, null) : f.bookingRules();
    directory.facilityChanged(f.facilityId(), new FacilityRef.Data(
        f.kind() == null ? "OTHER" : f.kind(),
        f.name() == null ? "Facility" : f.name(),
        f.capacity() == null ? 1 : f.capacity(),
        Boolean.TRUE.equals(f.chargeable()), f.chargePaise() == null ? 0 : f.chargePaise(),
        r.slotMinutes() == null ? 60 : r.slotMinutes(),
        r.maxAdvanceDays() == null ? 14 : r.maxAdvanceDays(),
        r.maxPerFlatPerWeek() == null ? 0 : r.maxPerFlatPerWeek(),
        f.status() == null ? "ACTIVE" : f.status()));
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = {"society.flat.created", "society.flat.updated"})
  void onFlat(CloudEvent<Flat> e) {
    directory.flatChanged(e.data().flatId(), e.data().towerId(), e.data().label());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.membership.created")
  void onMembershipCreated(CloudEvent<Membership> e) {
    Membership m = e.data();
    directory.membershipCreated(m.membershipId(), m.flatId(), m.userId(), m.kind());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.membership.ended")
  void onMembershipEnded(CloudEvent<Membership> e) {
    directory.membershipEnded(e.data().membershipId());
  }
}
