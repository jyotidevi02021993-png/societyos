package in.societyos.community.event.domain;

import in.societyos.community.common.CommunityDomainEvent;
import java.time.Instant;
import java.util.UUID;

/** {@code community.event.created} (catalogue: eventId, kind, title, startsAt, endsAt, feePaise). */
public record EventCreated(UUID eventId, String kind, String title, Instant startsAt, Instant endsAt, long feePaise)
    implements CommunityDomainEvent {
  @Override public String type() { return "community.event.created"; }
  @Override public UUID aggregateId() { return eventId; }
}
