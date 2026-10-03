package in.societyos.vendor.receiving.infrastructure;

import in.societyos.vendor.receiving.domain.GrnLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrnLineRepository extends JpaRepository<GrnLine, UUID> {

  List<GrnLine> findByGrnId(UUID grnId);
}
