package in.societyos.vendor.invoicing.infrastructure;

import in.societyos.vendor.invoicing.domain.VendorInvoice;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface VendorInvoiceRepository extends JpaRepository<VendorInvoice, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from VendorInvoice i where i.id = :id")
  Optional<VendorInvoice> findForUpdate(UUID id);

  boolean existsByVendorIdAndVendorInvoiceNo(UUID vendorId, String vendorInvoiceNo);

  List<VendorInvoice> findTop200ByOrderByCreatedAtDesc();

  List<VendorInvoice> findTop200ByStatusOrderByCreatedAtDesc(VendorInvoice.Status status);

  List<VendorInvoice> findTop200ByVendorIdOrderByCreatedAtDesc(UUID vendorId);

  List<VendorInvoice> findByPoIdOrderByCreatedAtAsc(UUID poId);
}
