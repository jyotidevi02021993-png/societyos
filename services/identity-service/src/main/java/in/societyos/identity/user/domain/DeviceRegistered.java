package in.societyos.identity.user.domain;

import in.societyos.identity.platform.events.DomainEvent;
import java.util.UUID;

/** {@code identity.device.registered}: notification-service keeps push tokens as a read model. */
public record DeviceRegistered(UUID deviceId, UUID userId, String platform, String pushToken)
    implements DomainEvent {

  @Override
  public String type() {
    return "identity.device.registered";
  }

  @Override
  public String context() {
    return "identity";
  }

  @Override
  public UUID aggregateId() {
    return userId;
  }
}
