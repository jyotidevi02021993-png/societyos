package in.societyos.billing.bill.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A snapshot line of a bill. Locked (immutable, DB trigger) once the bill is published. */
@Entity
@Table(name = "bill_line")
public class BillLine extends TenantEntity {

  @Column(name = "bill_id", nullable = false)
  private UUID billId;

  @Column(nullable = false)
  private String kind;

  private String code;

  @Column(nullable = false)
  private String description;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(name = "gst_paise", nullable = false)
  private long gstPaise;

  @Column(name = "source_ref")
  private UUID sourceRef;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "locked_at")
  private Instant lockedAt;

  protected BillLine() {}

  public BillLine(UUID billId, String kind, String code, String description, long amountPaise, long gstPaise,
      UUID sourceRef, int sortOrder) {
    this.billId = billId;
    this.kind = kind;
    this.code = code;
    this.description = description;
    this.amountPaise = amountPaise;
    this.gstPaise = gstPaise;
    this.sourceRef = sourceRef;
    this.sortOrder = sortOrder;
  }

  public void lock(Instant at) {
    lockedAt = at;
  }

  public UUID getBillId() { return billId; }
  public String getKind() { return kind; }
  public String getCode() { return code; }
  public String getDescription() { return description; }
  public long getAmountPaise() { return amountPaise; }
  public long getGstPaise() { return gstPaise; }
  public UUID getSourceRef() { return sourceRef; }
  public int getSortOrder() { return sortOrder; }
}
