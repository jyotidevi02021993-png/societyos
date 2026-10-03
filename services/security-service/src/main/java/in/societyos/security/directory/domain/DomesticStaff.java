package in.societyos.security.directory.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Domestic help registered by society-service ({@code society.domesticstaff.*}). The phone is not
 * in events (no PII); a guard or manager enrols it here so staff can be found by phone at the gate.
 */
@Entity
@Table(name = "domestic_staff")
public class DomesticStaff extends TenantEntity {

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String kind;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "flat_ids", nullable = false, columnDefinition = "uuid[]")
  private UUID[] flatIds = new UUID[0];

  @Column(name = "kyc_status")
  private String kycStatus;

  @Column(name = "photo_media_id")
  private UUID photoMediaId;

  @Column(nullable = false)
  private String status = "ACTIVE";

  @Column(name = "phone_enc")
  private String phoneEnc;

  @Column(name = "phone_hash")
  private String phoneHash;

  protected DomesticStaff() {}

  public DomesticStaff(UUID staffId) {
    super(staffId);
  }

  public void apply(
      String name, String kind, Collection<UUID> flatIds, String kycStatus, UUID photoMediaId, String status) {
    this.name = name == null ? "" : name;
    this.kind = kind == null ? "OTHER" : kind;
    this.flatIds = flatIds == null ? new UUID[0] : flatIds.toArray(UUID[]::new);
    this.kycStatus = kycStatus;
    this.photoMediaId = photoMediaId;
    if (status != null) {
      this.status = status;
    }
  }

  public void enrolPhone(String phoneEnc, String phoneHash) {
    this.phoneEnc = phoneEnc;
    this.phoneHash = phoneHash;
  }

  public boolean isBlocked() {
    return "BLOCKED".equals(status);
  }

  public String getName() {
    return name;
  }

  public String getKind() {
    return kind;
  }

  public List<UUID> getFlatIds() {
    return Arrays.asList(flatIds);
  }

  public String getKycStatus() {
    return kycStatus;
  }

  public UUID getPhotoMediaId() {
    return photoMediaId;
  }

  public String getStatus() {
    return status;
  }

  public String getPhoneEnc() {
    return phoneEnc;
  }

  public boolean hasPhone() {
    return phoneHash != null;
  }
}
