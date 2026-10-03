package in.societyos.workflow.directory.infrastructure;

import in.societyos.workflow.directory.application.RoleDirectory;
import in.societyos.workflow.platform.events.CloudEvent;
import in.societyos.workflow.platform.events.DomainEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Group {@code workflow.role-directory} (DLQ {@code sos.dlq.workflow.role-directory}). */
@Component
class RoleEventsListener {

  static final String TOPIC = "sos.identity.events.v1";
  static final String GROUP = "workflow.role-directory";

  record RoleData(UUID assignmentId, UUID userId, String roleCode) {}

  private final RoleDirectory roles;

  RoleEventsListener(RoleDirectory roles) {
    this.roles = roles;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "identity.role.assigned")
  void onAssigned(CloudEvent<RoleData> e) {
    if (e.societyId() != null) {
      roles.assigned(e.data().assignmentId(), e.data().userId(), e.data().roleCode());
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "identity.role.revoked")
  void onRevoked(CloudEvent<RoleData> e) {
    if (e.societyId() != null) {
      roles.revoked(e.data().assignmentId());
    }
  }
}
