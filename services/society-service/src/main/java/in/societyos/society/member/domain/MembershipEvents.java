package in.societyos.society.member.domain;

import in.societyos.society.common.SocietyEvent;
import java.util.UUID;

/** {@code society.membership.created/ended}: identity-service turns them into resident roles. */
public final class MembershipEvents {

  private MembershipEvents() {}

  public record MembershipCreated(UUID membershipId, UUID flatId, UUID userId, UUID residentId, String residentName,
      String kind, boolean isPrimary) implements SocietyEvent {
    @Override public String type() { return "society.membership.created"; }
    @Override public UUID aggregateId() { return membershipId; }
  }

  public record MembershipEnded(UUID membershipId, UUID flatId, UUID userId, UUID residentId, String residentName,
      String kind, boolean isPrimary) implements SocietyEvent {
    @Override public String type() { return "society.membership.ended"; }
    @Override public UUID aggregateId() { return membershipId; }
  }

  public static MembershipCreated created(FlatMembership m, Resident r) {
    return new MembershipCreated(m.getId(), m.getFlatId(), m.getUserId(), r.getId(), r.getName(), m.getKind(),
        m.isPrimary());
  }

  public static MembershipEnded ended(FlatMembership m, Resident r) {
    return new MembershipEnded(m.getId(), m.getFlatId(), m.getUserId(), r.getId(), r.getName(), m.getKind(),
        m.isPrimary());
  }
}
