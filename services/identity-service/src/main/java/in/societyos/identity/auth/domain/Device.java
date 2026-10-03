package in.societyos.identity.auth.domain;

import in.societyos.identity.platform.jpa.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A phone, browser or gate edge box bound to a user at login. Refresh tokens belong to one device. */
@Entity
@Table(name = "device")
public class Device extends GlobalEntity {

  public enum Platform {
    ANDROID,
    IOS,
    WEB,
    EDGE
  }

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String platform;

  private String name;

  @Column(name = "push_token")
  private String pushToken;

  @Column(name = "bound_at", nullable = false)
  private Instant boundAt;

  @Column(name = "last_seen_at")
  private Instant lastSeenAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected Device() {}

  public static Device bind(UUID userId, Platform platform, String name, String pushToken) {
    Device d = new Device();
    d.userId = userId;
    d.platform = platform.name();
    d.name = name;
    d.pushToken = pushToken;
    d.boundAt = Instant.now();
    d.lastSeenAt = d.boundAt;
    return d;
  }

  public boolean isActiveFor(UUID user) {
    return revokedAt == null && userId.equals(user);
  }

  public void seen() {
    lastSeenAt = Instant.now();
  }

  public void revoke() {
    if (revokedAt == null) {
      revokedAt = Instant.now();
    }
  }

  public void updatePushToken(String token) {
    this.pushToken = token;
  }

  public UUID getUserId() {
    return userId;
  }

  public Platform getPlatform() {
    return Platform.valueOf(platform);
  }

  public String getName() {
    return name;
  }

  public String getPushToken() {
    return pushToken;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
