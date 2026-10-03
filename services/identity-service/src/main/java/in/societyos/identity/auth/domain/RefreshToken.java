package in.societyos.identity.auth.domain;

import in.societyos.identity.platform.jpa.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Opaque refresh token, stored only as a SHA-256 hash. Each use rotates it; presenting an
 * already-rotated token means it was copied, so the whole device session is revoked.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken extends GlobalEntity {

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "rotated_at")
  private Instant rotatedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected RefreshToken() {}

  public static RefreshToken issue(UUID userId, UUID deviceId, String tokenHash, Instant expiresAt) {
    RefreshToken t = new RefreshToken();
    t.userId = userId;
    t.deviceId = deviceId;
    t.tokenHash = tokenHash;
    t.expiresAt = expiresAt;
    return t;
  }

  /** Rotated or revoked tokens must never be accepted again. */
  public boolean isSpent() {
    return rotatedAt != null || revokedAt != null;
  }

  public boolean isExpired(Instant now) {
    return !expiresAt.isAfter(now);
  }

  public void markRotated() {
    rotatedAt = Instant.now();
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getDeviceId() {
    return deviceId;
  }
}
