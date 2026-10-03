package in.societyos.billing.roster.infrastructure;

import in.societyos.billing.platform.events.CloudEvent;
import in.societyos.billing.platform.events.DomainEventListener;
import in.societyos.billing.roster.application.RosterProjection;
import in.societyos.billing.roster.application.SocietyEventData;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Group {@code billing.roster} on {@code sos.society.events.v1}; DLQ {@code sos.dlq.billing.roster}. */
@Component
class SocietyEventsListener {

  static final String TOPIC = "sos.society.events.v1";
  static final String GROUP = "billing.roster";

  private final RosterProjection projection;

  SocietyEventsListener(RosterProjection projection) {
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
    projection.membershipEnded(e.data(), e.time() == null ? Instant.now() : e.time());
  }
}
