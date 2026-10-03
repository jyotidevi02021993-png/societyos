package in.societyos.security.gatepass.domain;

import in.societyos.security.common.SecurityEvent;
import java.time.Instant;
import java.util.UUID;

/** {@code security.pass.created}: no code, QR token or guest phone in the event. */
public final class PassEvents {

  private PassEvents() {}

  public record PassCreated(UUID passId, UUID flatId, String kind, Instant validFrom, Instant validTo, int maxUses)
      implements SecurityEvent {
    @Override public String type() { return "security.pass.created"; }
    @Override public UUID aggregateId() { return passId; }
  }

  public static PassCreated created(GatePass p) {
    return new PassCreated(p.getId(), p.getFlatId(), p.getKind(), p.getValidFrom(), p.getValidTo(), p.getMaxUses());
  }
}
