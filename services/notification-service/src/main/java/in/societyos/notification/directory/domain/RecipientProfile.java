package in.societyos.notification.directory.domain;

import in.societyos.notification.platform.jpa.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Global (reviewed): a user's preferred language from {@code identity.user.registered}; no contact data. */
@Entity
@Table(name = "recipient_profile")
public class RecipientProfile extends GlobalEntity {

  @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
  @Column(name = "preferred_lang", nullable = false) private String preferredLang;

  protected RecipientProfile() {}

  public RecipientProfile(UUID userId, String preferredLang) {
    this.userId = userId;
    setPreferredLang(preferredLang);
  }

  public void setPreferredLang(String lang) {
    this.preferredLang = "hi".equalsIgnoreCase(lang) ? "hi" : "en";
  }

  public UUID getUserId() { return userId; }
  public String getPreferredLang() { return preferredLang; }
}
