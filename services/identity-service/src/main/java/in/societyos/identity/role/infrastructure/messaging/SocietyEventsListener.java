package in.societyos.identity.role.infrastructure.messaging;

import in.societyos.identity.role.application.RoleService;
import in.societyos.identity.role.domain.RoleAssignment;
import in.societyos.identity.platform.events.CloudEvent;
import in.societyos.identity.platform.events.DomainEventListener;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps roles in step with society-service:
 * society.created → provision default roles; membership created/ended → resident role on/off.
 */
@Component
class SocietyEventsListener {

  private static final Logger log = LoggerFactory.getLogger(SocietyEventsListener.class);
  static final String TOPIC = "sos.society.events.v1";
  static final String GROUP = "identity.society-roles";

  /** Consumer-side view of {@code society.created}. */
  record SocietyCreated(UUID societyId, String name) {}

  /** Consumer-side view of {@code society.membership.created/ended}; kind = OWNER | TENANT | FAMILY. */
  record MembershipChanged(UUID membershipId, UUID flatId, UUID userId, String kind) {}

  private final RoleService roles;

  SocietyEventsListener(RoleService roles) {
    this.roles = roles;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.created")
  void onSocietyCreated(CloudEvent<SocietyCreated> event) {
    roles.provisionSociety();
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.membership.created")
  void onMembershipCreated(CloudEvent<MembershipChanged> event) {
    MembershipChanged m = event.data();
    if (m.userId() == null) {
      log.warn("Membership {} has no user id; resident role not assigned", m.membershipId());
      return;
    }
    roles.assign(m.userId(), "RESIDENT_" + m.kind(), RoleAssignment.Source.MEMBERSHIP, m.membershipId());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.membership.ended")
  void onMembershipEnded(CloudEvent<MembershipChanged> event) {
    roles.revokeBySource(event.data().membershipId());
  }
}
