package in.societyos.vendor.purchasing.domain;

import in.societyos.vendor.common.VendorEvent;
import java.util.UUID;

/**
 * Purchase order events, exactly as in contracts/events/CATALOGUE.md (vendor section). workflow-service
 * starts the approval from {@code vendor.po.submitted} (reads poId, number, vendorId, amountPaise).
 */
public final class PoEvents {

  private PoEvents() {}

  public record PoSubmitted(UUID poId, String number, UUID vendorId, long amountPaise) implements VendorEvent {
    @Override public String type() { return "vendor.po.submitted"; }
    @Override public UUID aggregateId() { return poId; }
  }

  public record PoApproved(UUID poId, String number, UUID vendorId, long amountPaise) implements VendorEvent {
    @Override public String type() { return "vendor.po.approved"; }
    @Override public UUID aggregateId() { return poId; }
  }

  public record PoRejected(UUID poId, String number, UUID vendorId, long amountPaise) implements VendorEvent {
    @Override public String type() { return "vendor.po.rejected"; }
    @Override public UUID aggregateId() { return poId; }
  }

  public static PoSubmitted submitted(PurchaseOrder po) {
    return new PoSubmitted(po.getId(), po.getNumber(), po.getVendorId(), po.getTotalPaise());
  }

  public static VendorEvent decided(PurchaseOrder po) {
    return po.getStatus() == PurchaseOrder.Status.APPROVED
        ? new PoApproved(po.getId(), po.getNumber(), po.getVendorId(), po.getTotalPaise())
        : new PoRejected(po.getId(), po.getNumber(), po.getVendorId(), po.getTotalPaise());
  }
}
