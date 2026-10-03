package in.societyos.notification.directory.infrastructure;

import in.societyos.notification.directory.application.RecipientDirectory;
import in.societyos.notification.platform.events.CloudEvent;
import in.societyos.notification.platform.events.DomainEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Group {@code notification.identity} on {@code sos.identity.events.v1} (DLQ
 * {@code sos.dlq.notification.identity}): push tokens, preferred language and role holders.
 */
@Component
class IdentityEventsListener {

  static final String TOPIC = "sos.identity.events.v1";
  static final String GROUP = "notification.identity";

  record Device(UUID deviceId, UUID userId, String platform, String pushToken) {}

  record User(UUID userId, String preferredLang) {}

  record Role(UUID assignmentId, UUID userId, String roleCode) {}

  private final RecipientDirectory directory;

  IdentityEventsListener(RecipientDirectory directory) {
    this.directory = directory;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "identity.device.registered")
  void onDevice(CloudEvent<Device> e) {
    Device d = e.data();
    directory.deviceRegistered(d.deviceId(), d.userId(), d.platform(), d.pushToken());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "identity.user.registered")
  void onUser(CloudEvent<User> e) {
    directory.userRegistered(e.data().userId(), e.data().preferredLang());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "identity.role.assigned")
  void onRoleAssigned(CloudEvent<Role> e) {
    if (e.societyId() != null) {
      directory.roleAssigned(e.data().assignmentId(), e.data().userId(), e.data().roleCode());
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "identity.role.revoked")
  void onRoleRevoked(CloudEvent<Role> e) {
    if (e.societyId() != null) {
      directory.roleRevoked(e.data().assignmentId());
    }
  }
}
