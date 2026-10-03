package in.societyos.identity.user.domain;

import in.societyos.identity.platform.events.DomainEvent;
import java.util.UUID;

/** {@code identity.user.registered}: carries no phone or e-mail (PII stays in identity). */
public record UserRegistered(UUID userId, String channel, String preferredLang) implements DomainEvent {

  @Override
  public String type() {
    return "identity.user.registered";
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
