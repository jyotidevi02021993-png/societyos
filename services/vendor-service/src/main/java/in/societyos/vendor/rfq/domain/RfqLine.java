package in.societyos.vendor.rfq.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** An item asked for in an RFQ. */
@Entity
@Table(name = "rfq_line")
public class RfqLine extends TenantEntity {

  @Column(name = "rfq_id", nullable = false, updatable = false)
  private UUID rfqId;
  @Column(name = "line_no", nullable = false, updatable = false)
  private int lineNo;
  @Column(name = "item_code", updatable = false)
  private String itemCode;
  @Column(name = "spare_id", updatable = false)
  private UUID spareId;
  @Column(nullable = false, updatable = false)
  private String description;
  @Column(nullable = false, updatable = false)
  private int qty;
  @Column(nullable = false, updatable = false)
  private String unit;

  protected RfqLine() {}

  public static RfqLine of(UUID rfqId, int lineNo, String itemCode, UUID spareId, String description, int qty,
      String unit) {
    if (qty <= 0) {
      throw ProblemException.badRequest("INVALID_QTY", "qty must be positive");
    }
    if (description == null && itemCode == null) {
      throw ProblemException.badRequest("INVALID_LINE", "Each line needs a description or an item code");
    }
    RfqLine l = new RfqLine();
    l.rfqId = rfqId;
    l.lineNo = lineNo;
    l.itemCode = itemCode;
    l.spareId = spareId;
    l.description = description != null ? description : itemCode;
    l.qty = qty;
    l.unit = unit == null ? "NOS" : unit;
    return l;
  }

  public UUID getRfqId() { return rfqId; }
  public int getLineNo() { return lineNo; }
  public String getItemCode() { return itemCode; }
  public UUID getSpareId() { return spareId; }
  public String getDescription() { return description; }
  public int getQty() { return qty; }
  public String getUnit() { return unit; }
}
