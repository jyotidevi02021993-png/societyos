package in.societyos.vendor.invoicing.infrastructure;

import in.societyos.vendor.invoicing.domain.VendorInvoiceLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VendorInvoiceLineRepository extends JpaRepository<VendorInvoiceLine, UUID> {

  List<VendorInvoiceLine> findByInvoiceId(UUID invoiceId);

  /** Quantity already billed on a PO line by other invoices that are not rejected. */
  @Query("""
      select coalesce(sum(l.qty), 0) from VendorInvoiceLine l, VendorInvoice i
      where l.invoiceId = i.id and l.poLineId = :poLineId and i.id <> :excludeInvoiceId
        and i.status <> in.societyos.vendor.invoicing.domain.VendorInvoice.Status.REJECTED
      """)
  long billedQty(UUID poLineId, UUID excludeInvoiceId);
}
