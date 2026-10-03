package in.societyos.security.visitor.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person who came to the gate. The phone is stored encrypted ({@code phone_enc}) with a keyed
 * hash for "returning visitor" lookups; it never leaves the service unmasked. Purged after the
 * society retention period counted from {@code last_seen_at}.
 */
@Entity
@Table(name = "visitor")
public class Visitor extends TenantEntity {

  @Column(nullable = false)
  private String name;

  @Column(name = "phone_enc")
  private String phoneEnc;

  @Column(name = "phone_hash")
  private String phoneHash;

  @Column(name = "photo_media_id")
  private UUID photoMediaId;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  protected Visitor() {}

  public Visitor(String name, String phoneEnc, String phoneHash, UUID photoMediaId, Instant at) {
    this.name = requireName(name);
    this.phoneEnc = phoneEnc;
    this.phoneHash = phoneHash;
    this.photoMediaId = photoMediaId;
    this.lastSeenAt = at;
  }

  /** A returning visitor: the latest name and photo win. */
  public void seenAgain(String name, UUID photoMediaId, Instant at) {
    if (name != null && !name.isBlank()) {
      this.name = requireName(name);
    }
    if (photoMediaId != null) {
      this.photoMediaId = photoMediaId;
    }
    if (lastSeenAt == null || at.isAfter(lastSeenAt)) {
      this.lastSeenAt = at;
    }
  }

  static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Visitor name is required");
    }
    String trimmed = name.trim();
    return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
  }

  public String getName() {
    return name;
  }

  public String getPhoneEnc() {
    return phoneEnc;
  }

  public String getPhoneHash() {
    return phoneHash;
  }

  public UUID getPhotoMediaId() {
    return photoMediaId;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }
}
