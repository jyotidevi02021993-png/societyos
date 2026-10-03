package in.societyos.vendor.rfq.infrastructure;

import in.societyos.vendor.rfq.domain.RfqLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RfqLineRepository extends JpaRepository<RfqLine, UUID> {

  List<RfqLine> findByRfqIdOrderByLineNoAsc(UUID rfqId);
}
