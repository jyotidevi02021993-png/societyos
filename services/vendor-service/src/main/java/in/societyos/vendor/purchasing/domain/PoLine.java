package in.societyos.vendor.purchasing.domain;

import in.societyos.vendor.common.Amounts;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One line of a purchase order. {@code receivedQty} grows with each GRN and never passes {@code qty}. */
@Entity
@Table(name = "po_line")
public class PoLine extends TenantEntity {

  @Column(name = "po_id", nullable = false, updatable = false)
  private UUID poId;
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
  @Column(name = "unit_price_paise", nullable = false, updatable = false)
  private long unitPricePaise;
  @Column(name = "gst_percent", nullable = false, updatable = false)
  private int gstPercent;
  @Column(name = "received_qty", nullable = false)
  private int receivedQty;

  protected PoLine() {}

  public static PoLine of(UUID poId, int lineNo, String itemCode, UUID spareId, String description, int qty,
      String unit, long unitPricePaise, int gstPercent) {
    if (description == null && itemCode == null) {
      throw ProblemException.badRequest("INVALID_LINE", "Each line needs a description or an item code");
    }
    if (qty <= 0) {
      throw ProblemException.badRequest("INVALID_QTY", "qty must be positive");
    }
    if (unitPricePaise < 0) {
      throw ProblemException.badRequest("INVALID_PRICE", "unitPricePaise must not be negative");
    }
    if (gstPercent < 0 || gstPercent > 28) {
      throw ProblemException.badRequest("INVALID_GST", "gstPercent is 0 to 28");
    }
    PoLine l = new PoLine();
    l.poId = poId;
    l.lineNo = lineNo;
    l.itemCode = itemCode;
    l.spareId = spareId;
    l.description = description != null ? description : itemCode;
    l.qty = qty;
    l.unit = unit == null ? "NOS" : unit;
    l.unitPricePaise = unitPricePaise;
    l.gstPercent = gstPercent;
    return l;
  }

  public long amountPaise() {
    return Amounts.linePaise(qty, unitPricePaise);
  }

  public long taxPaise() {
    return Amounts.gstPaise(amountPaise(), gstPercent);
  }

  public int pendingQty() {
    return qty - receivedQty;
  }

  /** Records a receipt against this line; more than what is still pending is refused. */
  public void receive(int received) {
    if (received < 0) {
      throw ProblemException.badRequest("INVALID_QTY", "Received qty must not be negative");
    }
    if (received > pendingQty()) {
      throw ProblemException.unprocessable("OVER_RECEIPT",
          "Line " + lineNo + ": receiving " + received + " but only " + pendingQty() + " pending");
    }
    receivedQty += received;
  }

  public boolean fullyReceived() {
    return receivedQty >= qty;
  }

  public UUID getPoId() { return poId; }
  public int getLineNo() { return lineNo; }
  public String getItemCode() { return itemCode; }
  public UUID getSpareId() { return spareId; }
  public String getDescription() { return description; }
  public int getQty() { return qty; }
  public String getUnit() { return unit; }
  public long getUnitPricePaise() { return unitPricePaise; }
  public int getGstPercent() { return gstPercent; }
  public int getReceivedQty() { return receivedQty; }
}
