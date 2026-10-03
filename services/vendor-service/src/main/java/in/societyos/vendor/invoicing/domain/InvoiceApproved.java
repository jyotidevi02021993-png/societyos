package in.societyos.vendor.invoicing.domain;

import in.societyos.vendor.common.VendorEvent;
import java.util.UUID;

/** {@code vendor.invoice.approved} (catalogue, vendor section). */
public record InvoiceApproved(UUID invoiceId, UUID vendorId, UUID poId, long amountPaise) implements VendorEvent {

  @Override public String type() { return "vendor.invoice.approved"; }
  @Override public UUID aggregateId() { return invoiceId; }

  public static InvoiceApproved of(VendorInvoice i) {
    return new InvoiceApproved(i.getId(), i.getVendorId(), i.getPoId(), i.getTotalPaise());
  }
}
