package in.societyos.society.household.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A maid, cook, driver ... who works for one or more flats. One record per person per society,
 * found by phone hash, so the gate sees a single staff member however many flats they serve.
 * The phone is stored encrypted and never leaves this service unmasked.
 */
@Entity
@Table(name = "domestic_staff")
public class DomesticStaff extends TenantEntity {

  public static final Set<String> KINDS = Set.of("MAID", "COOK", "DRIVER", "NANNY", "OTHER");
  public static final Set<String> KYC_STATUSES = Set.of("PENDING", "VERIFIED", "REJECTED");
  public static final Set<String> STATUSES = Set.of("ACTIVE", "BLOCKED");

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String kind;

  @Column(name = "phone_enc", nullable = false)
  private String phoneEnc;

  @Column(name = "phone_hash", nullable = false, updatable = false)
  private String phoneHash;

  @Column(name = "photo_media_id")
  private UUID photoMediaId;

  @Column(name = "kyc_status", nullable = false)
  private String kycStatus;

  @Column(nullable = false)
  private String status;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "domestic_staff_flat", joinColumns = @JoinColumn(name = "staff_id"))
  @Column(name = "flat_id", nullable = false)
  private Set<UUID> flatIds = new LinkedHashSet<>();

  protected DomesticStaff() {}

  public DomesticStaff(String name, String kind, String phoneEnc, String phoneHash, UUID photoMediaId) {
    super(UuidV7.next());
    this.phoneEnc = phoneEnc;
    this.phoneHash = phoneHash;
    this.kycStatus = "PENDING";
    this.status = "ACTIVE";
    update(name, kind, photoMediaId);
  }

  public void update(String name, String kind, UUID photoMediaId) {
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("Unknown staff kind " + kind);
    }
    this.name = name;
    this.kind = kind;
    if (photoMediaId != null) {
      this.photoMediaId = photoMediaId;
    }
  }

  public void setKycStatus(String kycStatus) {
    if (!KYC_STATUSES.contains(kycStatus)) {
      throw new IllegalArgumentException("Unknown KYC status " + kycStatus);
    }
    this.kycStatus = kycStatus;
  }

  public void setStatus(String status) {
    if (!STATUSES.contains(status)) {
      throw new IllegalArgumentException("Unknown staff status " + status);
    }
    this.status = status;
  }

  /** Returns true when the flat was newly added. */
  public boolean addFlat(UUID flatId) {
    return flatIds.add(flatId);
  }

  /** Returns true when the flat was linked. */
  public boolean removeFlat(UUID flatId) {
    return flatIds.remove(flatId);
  }

  public String getName() { return name; }
  public String getKind() { return kind; }
  public String getPhoneEnc() { return phoneEnc; }
  public String getPhoneHash() { return phoneHash; }
  public UUID getPhotoMediaId() { return photoMediaId; }
  public String getKycStatus() { return kycStatus; }
  public String getStatus() { return status; }
  public Set<UUID> getFlatIds() { return Set.copyOf(flatIds); }
}
