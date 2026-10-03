package in.societyos.society.society.domain;

import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A housing society: the tenant itself. Its row is tenant-scoped like any other
 * ({@code society_id = id}), so a user only ever sees the societies their token grants.
 * {@code settings} is the JSON form of {@link SocietySettings}.
 */
@Entity
@Table(name = "society")
public class Society extends TenantEntity {

  @Column(nullable = false)
  private String name;

  @Column(name = "legal_name")
  private String legalName;

  private String address;

  @Column(nullable = false)
  private String city;

  @Column(nullable = false)
  private String state;

  private String pin;

  @Column(nullable = false)
  private String timezone;

  @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String settingsJson;

  @Column(nullable = false)
  private String status;

  protected Society() {}

  /** The id is also the tenant id: create it while that society is the active one. */
  public Society(UUID id, Profile profile, String settingsJson) {
    super(id);
    apply(profile);
    this.settingsJson = settingsJson;
    this.status = "ACTIVE";
  }

  /** Editable profile fields. */
  public record Profile(
      String name, String legalName, String address, String city, String state, String pin, String timezone) {}

  public void apply(Profile p) {
    this.name = p.name();
    this.legalName = p.legalName();
    this.address = p.address();
    this.city = p.city();
    this.state = p.state();
    this.pin = p.pin();
    this.timezone = p.timezone();
  }

  public void replaceSettings(String json) {
    this.settingsJson = json;
  }

  public Profile profile() {
    return new Profile(name, legalName, address, city, state, pin, timezone);
  }

  public String getName() {
    return name;
  }

  public String getCity() {
    return city;
  }

  public String getState() {
    return state;
  }

  public String getTimezone() {
    return timezone;
  }

  public String getSettingsJson() {
    return settingsJson;
  }

  public String getStatus() {
    return status;
  }
}
