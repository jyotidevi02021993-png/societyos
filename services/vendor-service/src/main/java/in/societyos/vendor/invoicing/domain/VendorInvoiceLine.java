package in.societyos.vendor.invoicing.domain;

import in.societyos.vendor.common.Amounts;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One billed PO line on a vendor invoice. */
@Entity
@Table(name = "vendor_invoice_line")
public class VendorInvoiceLine extends TenantEntity {

  @Column(name = "invoice_id", nullable = false, updatable = false)
  private UUID invoiceId;
  @Column(name = "po_line_id", nullable = false, updatable = false)
  private UUID poLineId;
  @Column(nullable = false, updatable = false)
  private int qty;
  @Column(name = "unit_price_paise", nullable = false, updatable = false)
  private long unitPricePaise;
  @Column(name = "tax_paise", nullable = false, updatable = false)
  private long taxPaise;

  protected VendorInvoiceLine() {}

  public static VendorInvoiceLine of(UUID invoiceId, UUID poLineId, int qty, long unitPricePaise, long taxPaise) {
    if (qty <= 0 || unitPricePaise < 0 || taxPaise < 0) {
      throw ProblemException.badRequest("INVALID_LINE", "qty must be positive, price and tax not negative");
    }
    VendorInvoiceLine l = new VendorInvoiceLine();
    l.invoiceId = invoiceId;
    l.poLineId = poLineId;
    l.qty = qty;
    l.unitPricePaise = unitPricePaise;
    l.taxPaise = taxPaise;
    return l;
  }

  public long amountPaise() {
    return Amounts.linePaise(qty, unitPricePaise);
  }

  public ThreeWayMatch.InvoiceLine toMatch() {
    return new ThreeWayMatch.InvoiceLine(poLineId, qty, unitPricePaise, taxPaise);
  }

  public UUID getInvoiceId() { return invoiceId; }
  public UUID getPoLineId() { return poLineId; }
  public int getQty() { return qty; }
  public long getUnitPricePaise() { return unitPricePaise; }
  public long getTaxPaise() { return taxPaise; }
}
