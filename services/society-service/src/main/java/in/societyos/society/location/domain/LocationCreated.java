package in.societyos.society.location.domain;

import in.societyos.society.common.SocietyEvent;
import java.util.UUID;

/** {@code society.location.created}. */
public record LocationCreated(UUID locationId, String kind, String name, UUID towerId, UUID parentId)
    implements SocietyEvent {
  @Override public String type() { return "society.location.created"; }
  @Override public UUID aggregateId() { return locationId; }
}
