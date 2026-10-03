package in.societyos.vendor.vendor.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A vendor of this society (Vendor Registration form). PAN, phone and e-mail are stored
 * encrypted ({@code FieldCrypto}); the application layer encrypts before calling the setters.
 */
@Entity
@Table(name = "vendor")
public class Vendor extends TenantEntity {

  public static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "BLACKLISTED");
  public static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

  @Column(nullable = false, updatable = false)
  private String code;
  @Column(nullable = false)
  private String name;
  @Column(nullable = false)
  private String category;
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "work_scopes", nullable = false, columnDefinition = "text[]")
  private String[] workScopes = new String[0];
  private String gstin;
  @Column(name = "pan_enc")
  private String panEnc;
  @Column(name = "contact_name")
  private String contactName;
  @Column(name = "contact_phone_enc")
  private String contactPhoneEnc;
  @Column(name = "contact_email_enc")
  private String contactEmailEnc;
  private String address;
  @Column(name = "agreement_ref")
  private String agreementRef;
  @Column(name = "agreement_valid_until")
  private LocalDate agreementValidUntil;
  @Column(name = "risk_level", nullable = false)
  private String riskLevel = "LOW";
  @Column(nullable = false)
  private String status = "ACTIVE";
  @Column(name = "rating_count", nullable = false)
  private int ratingCount;
  @Column(name = "rating_total", nullable = false)
  private int ratingTotal;

  protected Vendor() {}

  public static Vendor register(String code, String name, String category) {
    if (code == null || name == null || category == null) {
      throw ProblemException.badRequest("INVALID_VENDOR", "code, name and category are required");
    }
    Vendor v = new Vendor();
    v.code = code;
    v.name = name;
    v.category = category;
    return v;
  }

  public void describe(String name, String category, List<String> workScopes, String gstin, String contactName,
      String address, String agreementRef, LocalDate agreementValidUntil, String riskLevel) {
    if (name == null || category == null) {
      throw ProblemException.badRequest("INVALID_VENDOR", "name and category are required");
    }
    if (riskLevel != null && !RISK_LEVELS.contains(riskLevel)) {
      throw ProblemException.badRequest("INVALID_RISK_LEVEL", "riskLevel is one of " + RISK_LEVELS);
    }
    if (gstin != null && !gstin.matches("[0-9]{2}[A-Z0-9]{13}")) {
      throw ProblemException.badRequest("INVALID_GSTIN", "GSTIN must be 15 characters");
    }
    this.name = name;
    this.category = category;
    this.workScopes = workScopes == null ? new String[0] : workScopes.toArray(String[]::new);
    this.gstin = gstin;
    this.contactName = contactName;
    this.address = address;
    this.agreementRef = agreementRef;
    this.agreementValidUntil = agreementValidUntil;
    if (riskLevel != null) {
      this.riskLevel = riskLevel;
    }
  }

  /** Encrypted values (FieldCrypto); a null argument keeps the stored value. */
  public void protectedContact(String panEnc, String phoneEnc, String emailEnc) {
    if (panEnc != null) this.panEnc = panEnc;
    if (phoneEnc != null) this.contactPhoneEnc = phoneEnc;
    if (emailEnc != null) this.contactEmailEnc = emailEnc;
  }

  public void changeStatus(String newStatus) {
    if (!STATUSES.contains(newStatus)) {
      throw ProblemException.badRequest("INVALID_STATUS", "status is one of " + STATUSES);
    }
    this.status = newStatus;
  }

  public void rate(int score) {
    if (score < 1 || score > 5) {
      throw ProblemException.badRequest("INVALID_SCORE", "score is 1 to 5");
    }
    ratingCount++;
    ratingTotal += score;
  }

  /** Average rating to one decimal, or null before the first rating. */
  public Double rating() {
    return ratingCount == 0 ? null : Math.round(ratingTotal * 10.0 / ratingCount) / 10.0;
  }

  public boolean isActive() {
    return "ACTIVE".equals(status);
  }

  /** Purchase orders and quotes are only raised with active vendors. */
  public void requireActive() {
    if (!isActive()) {
      throw ProblemException.unprocessable("VENDOR_NOT_ACTIVE", "Vendor " + code + " is " + status);
    }
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public String getCategory() { return category; }
  public List<String> getWorkScopes() { return List.of(workScopes); }
  public String getGstin() { return gstin; }
  public String getPanEnc() { return panEnc; }
  public String getContactName() { return contactName; }
  public String getContactPhoneEnc() { return contactPhoneEnc; }
  public String getContactEmailEnc() { return contactEmailEnc; }
  public String getAddress() { return address; }
  public String getAgreementRef() { return agreementRef; }
  public LocalDate getAgreementValidUntil() { return agreementValidUntil; }
  public String getRiskLevel() { return riskLevel; }
  public String getStatus() { return status; }
  public int getRatingCount() { return ratingCount; }
}
