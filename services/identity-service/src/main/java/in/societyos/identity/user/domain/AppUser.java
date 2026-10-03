package in.societyos.identity.user.domain;

import in.societyos.identity.platform.jpa.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;

/**
 * A person. Global, not per society: the same resident may own a flat in one society and rent
 * in another. Society membership lives in role assignments.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends GlobalEntity {

  public static final String SUPER_ADMIN = "SUPER_ADMIN";

  @Column(name = "phone_e164")
  private String phoneE164;

  private String email;
  private String name;
  private String status = "ACTIVE";

  @Column(name = "preferred_lang")
  private String preferredLang = "en";

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "mfa_secret")
  private String mfaSecret;

  @Column(name = "platform_role")
  private String platformRole;

  @Column(name = "permission_version")
  private int permissionVersion = 1;

  @Column(name = "last_society_id")
  private UUID lastSocietyId;

  protected AppUser() {}

  public static AppUser withPhone(String phoneE164, String name) {
    AppUser u = new AppUser();
    u.phoneE164 = phoneE164;
    u.name = name;
    return u;
  }

  public static AppUser admin(String email, String name, String passwordHash) {
    AppUser u = new AppUser();
    u.email = email.toLowerCase(Locale.ROOT);
    u.name = name;
    u.passwordHash = passwordHash;
    return u;
  }

  public boolean isBlocked() {
    return "BLOCKED".equals(status);
  }

  public boolean isPlatformAdmin() {
    return SUPER_ADMIN.equals(platformRole);
  }

  public void grantPlatformAdmin() {
    this.platformRole = SUPER_ADMIN;
    bumpPermissionVersion();
  }

  public void bumpPermissionVersion() {
    permissionVersion++;
  }

  public void rememberSociety(UUID societyId) {
    this.lastSocietyId = societyId;
  }

  public void updateProfile(String name, String preferredLang) {
    if (name != null && !name.isBlank()) {
      this.name = name.trim();
    }
    if (preferredLang != null) {
      if (!preferredLang.equals("en") && !preferredLang.equals("hi")) {
        throw new IllegalArgumentException("Unsupported language: " + preferredLang);
      }
      this.preferredLang = preferredLang;
    }
  }

  public void setMfaSecret(String secret) {
    this.mfaSecret = secret;
  }

  public void block() {
    this.status = "BLOCKED";
  }

  public String getPhoneE164() {
    return phoneE164;
  }

  public String getEmail() {
    return email;
  }

  public String getName() {
    return name;
  }

  public String getStatus() {
    return status;
  }

  public String getPreferredLang() {
    return preferredLang;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getMfaSecret() {
    return mfaSecret;
  }

  public boolean hasMfa() {
    return mfaSecret != null && !mfaSecret.isBlank();
  }

  public String getPlatformRole() {
    return platformRole;
  }

  public int getPermissionVersion() {
    return permissionVersion;
  }

  public UUID getLastSocietyId() {
    return lastSocietyId;
  }
}
