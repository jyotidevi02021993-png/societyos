package in.societyos.vendor.purchasing.infrastructure;

import in.societyos.vendor.purchasing.domain.PoLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoLineRepository extends JpaRepository<PoLine, UUID> {

  List<PoLine> findByPoIdOrderByLineNoAsc(UUID poId);
}
