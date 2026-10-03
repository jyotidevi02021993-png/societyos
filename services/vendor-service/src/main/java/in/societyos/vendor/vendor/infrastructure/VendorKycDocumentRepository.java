package in.societyos.vendor.vendor.infrastructure;

import in.societyos.vendor.vendor.domain.VendorKycDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorKycDocumentRepository extends JpaRepository<VendorKycDocument, UUID> {

  List<VendorKycDocument> findByVendorIdOrderByCreatedAtAsc(UUID vendorId);
}
