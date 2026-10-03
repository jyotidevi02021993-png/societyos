package in.societyos.vendor.rfq.infrastructure;

import in.societyos.vendor.rfq.domain.QuoteLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteLineRepository extends JpaRepository<QuoteLine, UUID> {

  List<QuoteLine> findByQuoteIdIn(Collection<UUID> quoteIds);

  List<QuoteLine> findByQuoteId(UUID quoteId);
}
