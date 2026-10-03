package in.societyos.security.directory.infrastructure;

import in.societyos.security.directory.application.DirectoryProjection;
import in.societyos.security.directory.application.SocietyEventData;
import in.societyos.security.platform.events.CloudEvent;
import in.societyos.security.platform.events.DomainEventListener;
import org.springframework.stereotype.Component;

/**
 * Group {@code security.staff-roles} on {@code sos.identity.events.v1} (DLQ
 * {@code sos.dlq.security.staff-roles}): who is a guard or manager, to address SOS alerts.
 */
@Component
class IdentityEventsListener {

  static final String TOPIC = "sos.identity.events.v1";
  static final String GROUP = "security.staff-roles";

  private final DirectoryProjection projection;

  IdentityEventsListener(DirectoryProjection projection) {
    this.projection = projection;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "identity.role.assigned")
  void onAssigned(CloudEvent<SocietyEventData.RoleAssigned> e) {
    if (e.societyId() != null) {
      projection.roleAssigned(e.data());
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "identity.role.revoked")
  void onRevoked(CloudEvent<SocietyEventData.RoleAssigned> e) {
    if (e.societyId() != null) {
      projection.roleRevoked(e.data(), SocietyEventsListener.at(e));
    }
  }
}
