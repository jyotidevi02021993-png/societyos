package in.societyos.vendor.invoicing.infrastructure;

import in.societyos.vendor.invoicing.domain.VendorPayment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorPaymentRepository extends JpaRepository<VendorPayment, UUID> {

  List<VendorPayment> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);
}
