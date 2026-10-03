package in.societyos.vendor.rfq.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A request for quotation sent to one or more vendors. OPEN → AWARDED (creates a draft PO) | CANCELLED. */
@Entity
@Table(name = "rfq")
public class Rfq extends TenantEntity {

  public enum Status { OPEN, AWARDED, CANCELLED }

  @Column(nullable = false, updatable = false)
  private String number;
  @Column(nullable = false)
  private String title;
  @Column(columnDefinition = "text")
  private String description;
  @Column(name = "store_id")
  private UUID storeId;
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "invited_vendor_ids", nullable = false, columnDefinition = "uuid[]")
  private UUID[] invitedVendorIds = new UUID[0];
  @Column(name = "due_on")
  private LocalDate dueOn;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.OPEN;
  @Column(name = "awarded_quote_id")
  private UUID awardedQuoteId;

  protected Rfq() {}

  public static Rfq open(String number, String title, String description, UUID storeId, List<UUID> invited,
      LocalDate dueOn) {
    if (title == null) {
      throw ProblemException.badRequest("INVALID_RFQ", "title is required");
    }
    Rfq r = new Rfq();
    r.getId();
    r.number = number;
    r.title = title;
    r.description = description;
    r.storeId = storeId;
    r.invitedVendorIds = invited == null ? new UUID[0] : invited.stream().distinct().toArray(UUID[]::new);
    r.dueOn = dueOn;
    return r;
  }

  public boolean isInvited(UUID vendorId) {
    return List.of(invitedVendorIds).contains(vendorId);
  }

  public void requireOpen() {
    if (status != Status.OPEN) {
      throw ProblemException.unprocessable("RFQ_NOT_OPEN", "RFQ " + number + " is " + status);
    }
  }

  public void award(UUID quoteId) {
    requireOpen();
    status = Status.AWARDED;
    awardedQuoteId = quoteId;
  }

  public void cancel() {
    requireOpen();
    status = Status.CANCELLED;
  }

  public String getNumber() { return number; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
  public UUID getStoreId() { return storeId; }
  public List<UUID> getInvitedVendorIds() { return List.of(invitedVendorIds); }
  public LocalDate getDueOn() { return dueOn; }
  public Status getStatus() { return status; }
  public UUID getAwardedQuoteId() { return awardedQuoteId; }
}
