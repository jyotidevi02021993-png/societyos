package in.societyos.vendor.rfq.infrastructure;

import in.societyos.vendor.rfq.domain.Quote;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteRepository extends JpaRepository<Quote, UUID> {

  List<Quote> findByRfqIdOrderByTotalPaiseAsc(UUID rfqId);

  boolean existsByRfqIdAndVendorId(UUID rfqId, UUID vendorId);
}
