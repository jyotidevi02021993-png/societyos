package in.societyos.vendor.receiving.domain;

import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Quantity delivered for one PO line; only the accepted quantity counts as received. */
@Entity
@Table(name = "grn_line")
public class GrnLine extends TenantEntity {

  @Column(name = "grn_id", nullable = false, updatable = false)
  private UUID grnId;
  @Column(name = "po_line_id", nullable = false, updatable = false)
  private UUID poLineId;
  @Column(name = "received_qty", nullable = false, updatable = false)
  private int receivedQty;
  @Column(name = "accepted_qty", nullable = false, updatable = false)
  private int acceptedQty;

  protected GrnLine() {}

  public static GrnLine of(UUID grnId, UUID poLineId, int receivedQty, Integer acceptedQty) {
    int accepted = acceptedQty == null ? receivedQty : acceptedQty;
    if (receivedQty < 0 || accepted < 0) {
      throw ProblemException.badRequest("INVALID_QTY", "Quantities must not be negative");
    }
    if (accepted > receivedQty) {
      throw ProblemException.badRequest("INVALID_QTY", "Accepted qty cannot exceed received qty");
    }
    GrnLine l = new GrnLine();
    l.grnId = grnId;
    l.poLineId = poLineId;
    l.receivedQty = receivedQty;
    l.acceptedQty = accepted;
    return l;
  }

  public int rejectedQty() {
    return receivedQty - acceptedQty;
  }

  public UUID getGrnId() { return grnId; }
  public UUID getPoLineId() { return poLineId; }
  public int getReceivedQty() { return receivedQty; }
  public int getAcceptedQty() { return acceptedQty; }
}
