package in.societyos.security.sos.domain;

import in.societyos.security.common.SecurityEvent;
import java.time.Instant;
import java.util.UUID;

public final class SosEvents {

  private SosEvents() {}

  /** {@code security.sos.raised}: realtime pushes it to guard consoles and the manager dashboard. */
  public record SosRaised(UUID sosId, UUID flatId, UUID raisedBy, String kind, Instant at) implements SecurityEvent {
    @Override public String type() { return "security.sos.raised"; }
    @Override public UUID aggregateId() { return sosId; }
  }

  public static SosRaised raised(SosAlert s) {
    return new SosRaised(s.getId(), s.getFlatId(), s.getRaisedBy(), s.getKind(), s.getAt());
  }
}
