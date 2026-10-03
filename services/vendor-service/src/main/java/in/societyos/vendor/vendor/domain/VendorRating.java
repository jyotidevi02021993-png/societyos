package in.societyos.vendor.vendor.domain;

import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One rating of a vendor (1 to 5), optionally for a purchase order. */
@Entity
@Table(name = "vendor_rating")
public class VendorRating extends TenantEntity {

  @Column(name = "vendor_id", nullable = false, updatable = false)
  private UUID vendorId;
  @Column(name = "po_id", updatable = false)
  private UUID poId;
  @Column(nullable = false, updatable = false)
  private int score;
  @Column(columnDefinition = "text", updatable = false)
  private String comment;

  protected VendorRating() {}

  public VendorRating(UUID vendorId, UUID poId, int score, String comment) {
    this.vendorId = vendorId;
    this.poId = poId;
    this.score = score;
    this.comment = comment;
  }

  public UUID getVendorId() { return vendorId; }
  public UUID getPoId() { return poId; }
  public int getScore() { return score; }
  public String getComment() { return comment; }
}
