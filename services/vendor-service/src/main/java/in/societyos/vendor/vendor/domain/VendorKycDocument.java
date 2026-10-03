package in.societyos.vendor.vendor.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** A KYC document of a vendor, stored in media-service and referenced by {@code mediaId}. */
@Entity
@Table(name = "vendor_kyc_document")
public class VendorKycDocument extends TenantEntity {

  public static final Set<String> KINDS = Set.of("GST", "PAN", "AGREEMENT", "INSURANCE", "ESI_PF", "NDA", "LICENCE",
      "OTHER");

  @Column(name = "vendor_id", nullable = false, updatable = false)
  private UUID vendorId;
  @Column(nullable = false, updatable = false)
  private String kind;
  @Column(name = "media_id", nullable = false, updatable = false)
  private UUID mediaId;
  @Column(name = "valid_until")
  private LocalDate validUntil;
  @Column(name = "verified_at")
  private Instant verifiedAt;
  @Column(name = "verified_by")
  private UUID verifiedBy;

  protected VendorKycDocument() {}

  public static VendorKycDocument attach(UUID vendorId, String kind, UUID mediaId, LocalDate validUntil) {
    if (kind == null || !KINDS.contains(kind)) {
      throw ProblemException.badRequest("INVALID_KYC_KIND", "kind is one of " + KINDS);
    }
    VendorKycDocument d = new VendorKycDocument();
    d.vendorId = vendorId;
    d.kind = kind;
    d.mediaId = mediaId;
    d.validUntil = validUntil;
    return d;
  }

  public void verify(UUID by, Instant at) {
    this.verifiedBy = by;
    this.verifiedAt = at;
  }

  public UUID getVendorId() { return vendorId; }
  public String getKind() { return kind; }
  public UUID getMediaId() { return mediaId; }
  public LocalDate getValidUntil() { return validUntil; }
  public Instant getVerifiedAt() { return verifiedAt; }
  public UUID getVerifiedBy() { return verifiedBy; }
}
