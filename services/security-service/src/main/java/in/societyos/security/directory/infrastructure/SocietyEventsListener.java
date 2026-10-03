package in.societyos.security.directory.infrastructure;

import in.societyos.security.directory.application.DirectoryProjection;
import in.societyos.security.directory.application.SocietyEventData;
import in.societyos.security.platform.events.CloudEvent;
import in.societyos.security.platform.events.DomainEventListener;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Group {@code security.directory} on {@code sos.society.events.v1}; DLQ {@code sos.dlq.security.directory}. */
@Component
class SocietyEventsListener {

  static final String TOPIC = "sos.society.events.v1";
  static final String GROUP = "security.directory";

  private final DirectoryProjection projection;

  SocietyEventsListener(DirectoryProjection projection) {
    this.projection = projection;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.created")
  void onSocietyCreated(CloudEvent<SocietyEventData.SocietyCreated> e) {
    projection.societyCreated();
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.settings.updated")
  void onSettings(CloudEvent<SocietyEventData.SettingsUpdated> e) {
    projection.settingsUpdated(e.data());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = {"society.flat.created", "society.flat.updated"})
  void onFlat(CloudEvent<SocietyEventData.Flat> e) {
    projection.flat(e.data());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.membership.created")
  void onMembershipCreated(CloudEvent<SocietyEventData.Membership> e) {
    projection.membershipCreated(e.data());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.membership.ended")
  void onMembershipEnded(CloudEvent<SocietyEventData.Membership> e) {
    projection.membershipEnded(e.data(), at(e));
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.vehicle.registered")
  void onVehicleRegistered(CloudEvent<SocietyEventData.Vehicle> e) {
    projection.vehicleRegistered(e.data());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.vehicle.removed")
  void onVehicleRemoved(CloudEvent<SocietyEventData.Vehicle> e) {
    projection.vehicleRemoved(e.data(), at(e));
  }

  @DomainEventListener(topic = TOPIC, group = GROUP,
      type = {"society.domesticstaff.registered", "society.domesticstaff.updated"})
  void onDomesticStaff(CloudEvent<SocietyEventData.DomesticStaff> e) {
    projection.domesticStaff(e.data());
  }

  static Instant at(CloudEvent<?> e) {
    return e.time() == null ? Instant.now() : e.time();
  }
}
