package in.societyos.vendor.receiving.domain;

import in.societyos.vendor.common.VendorEvent;
import java.util.List;
import java.util.UUID;

/**
 * {@code vendor.grn.recorded} (catalogue, vendor section). inventory-service books a stock receipt
 * per line into {@code storeId}. Lines carry the accepted quantity at the PO unit price.
 */
public record GrnRecorded(UUID grnId, UUID poId, UUID storeId, List<Line> lines) implements VendorEvent {

  public record Line(String itemCode, UUID spareId, int qty, long unitCostPaise) {}

  @Override public String type() { return "vendor.grn.recorded"; }
  @Override public UUID aggregateId() { return grnId; }
}
