package in.societyos.realtime.socket.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which STOMP subscriptions a client may open. Private queues need only an authenticated user;
 * a society topic needs that society in the token and a permission for the screen behind it.
 */
public final class DestinationPolicy {

  private static final Pattern SOCIETY_TOPIC =
      Pattern.compile("^/topic/society\\.([0-9a-fA-F-]{36})\\.(gate|alerts)$");

  /** What a subscription requires: nothing more than a user, or a society plus any of the permissions. */
  public record Requirement(UUID societyId, List<String> anyPermission) {
    public boolean isPrivate() {
      return societyId == null;
    }
  }

  private DestinationPolicy() {}

  public static Optional<Requirement> requirementFor(String destination) {
    if (destination == null) {
      return Optional.empty();
    }
    if (destination.equals("/user/queue/gate") || destination.equals("/user/queue/alerts")) {
      return Optional.of(new Requirement(null, List.of()));
    }
    Matcher m = SOCIETY_TOPIC.matcher(destination);
    if (!m.matches()) {
      return Optional.empty();
    }
    UUID society;
    try {
      society = UUID.fromString(m.group(1));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    List<String> perms = switch (m.group(2)) {
      case "gate" -> List.of("gate:entry", "gate:log-view");
      default -> List.of("incident:manage", "dashboard:view", "gate:log-view");
    };
    return Optional.of(new Requirement(society, perms));
  }
}
