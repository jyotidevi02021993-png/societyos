package in.societyos.notification.directory.domain;

import in.societyos.notification.platform.jpa.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Global (reviewed): a push token from {@code identity.device.registered}, a platform-level event
 * (a phone belongs to a person, not a society). Used only to address push messages.
 */
@Entity
@Table(name = "device_token")
public class DeviceToken extends GlobalEntity {

  @Column(name = "device_id", nullable = false, updatable = false) private UUID deviceId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(nullable = false) private String platform;
  @Column(name = "push_token", nullable = false) private String pushToken;

  protected DeviceToken() {}

  public DeviceToken(UUID deviceId, UUID userId, String platform, String pushToken) {
    this.deviceId = deviceId;
    this.userId = userId;
    this.platform = platform;
    this.pushToken = pushToken;
  }

  public void update(UUID userId, String platform, String pushToken) {
    this.userId = userId;
    this.platform = platform;
    this.pushToken = pushToken;
  }

  public UUID getDeviceId() { return deviceId; }
  public UUID getUserId() { return userId; }
  public String getPlatform() { return platform; }
  public String getPushToken() { return pushToken; }
}
