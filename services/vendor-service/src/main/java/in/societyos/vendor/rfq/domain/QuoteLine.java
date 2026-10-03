package in.societyos.vendor.rfq.domain;

import in.societyos.vendor.common.Amounts;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A quoted price for one RFQ line. */
@Entity
@Table(name = "quote_line")
public class QuoteLine extends TenantEntity {

  @Column(name = "quote_id", nullable = false, updatable = false)
  private UUID quoteId;
  @Column(name = "rfq_line_id", nullable = false, updatable = false)
  private UUID rfqLineId;
  @Column(name = "unit_price_paise", nullable = false, updatable = false)
  private long unitPricePaise;
  @Column(name = "gst_percent", nullable = false, updatable = false)
  private int gstPercent;

  protected QuoteLine() {}

  public static QuoteLine of(UUID quoteId, UUID rfqLineId, long unitPricePaise, int gstPercent) {
    if (unitPricePaise < 0) {
      throw ProblemException.badRequest("INVALID_PRICE", "unitPricePaise must not be negative");
    }
    if (gstPercent < 0 || gstPercent > 28) {
      throw ProblemException.badRequest("INVALID_GST", "gstPercent is 0 to 28");
    }
    QuoteLine l = new QuoteLine();
    l.quoteId = quoteId;
    l.rfqLineId = rfqLineId;
    l.unitPricePaise = unitPricePaise;
    l.gstPercent = gstPercent;
    return l;
  }

  public long amountPaise(int qty) {
    return Amounts.linePaise(qty, unitPricePaise);
  }

  public long taxPaise(int qty) {
    return Amounts.gstPaise(amountPaise(qty), gstPercent);
  }

  public UUID getQuoteId() { return quoteId; }
  public UUID getRfqLineId() { return rfqLineId; }
  public long getUnitPricePaise() { return unitPricePaise; }
  public int getGstPercent() { return gstPercent; }
}
