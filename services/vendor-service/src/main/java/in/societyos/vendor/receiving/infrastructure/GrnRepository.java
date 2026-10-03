package in.societyos.vendor.receiving.infrastructure;

import in.societyos.vendor.receiving.domain.Grn;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrnRepository extends JpaRepository<Grn, UUID> {

  List<Grn> findByPoIdOrderByCreatedAtAsc(UUID poId);

  List<Grn> findTop200ByOrderByCreatedAtDesc();
}
