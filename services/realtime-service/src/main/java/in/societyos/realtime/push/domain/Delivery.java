package in.societyos.realtime.push.domain;

import java.util.UUID;

/**
 * One push: to a user's private queue ({@code /user/{id}/queue/gate}) or to a society topic
 * ({@code /topic/society.{id}.gate|alerts}).
 */
public record Delivery(Kind kind, UUID target, String channel) {

  public enum Kind {
    USER,
    SOCIETY
  }

  public static Delivery user(UUID userId, String queue) {
    return new Delivery(Kind.USER, userId, queue);
  }

  public static Delivery society(UUID societyId, String topic) {
    return new Delivery(Kind.SOCIETY, societyId, topic);
  }

  /** STOMP destination as the client subscribes to it. */
  public String destination() {
    return kind == Kind.USER ? "/queue/" + channel : "/topic/society." + target + "." + channel;
  }

  /** Redis pub/sub channel between realtime pods (docs/architecture/04 use 7). */
  public String redisChannel() {
    return kind == Kind.USER ? "rt:user:" + target + ":" + channel : "rt:society:" + target + ":" + channel;
  }

  public static Delivery fromRedisChannel(String redisChannel) {
    String[] p = redisChannel.split(":");
    if (p.length != 4 || !"rt".equals(p[0])) {
      throw new IllegalArgumentException("Not a realtime channel: " + redisChannel);
    }
    Kind kind = switch (p[1]) {
      case "user" -> Kind.USER;
      case "society" -> Kind.SOCIETY;
      default -> throw new IllegalArgumentException("Not a realtime channel: " + redisChannel);
    };
    return new Delivery(kind, UUID.fromString(p[2]), p[3]);
  }
}
