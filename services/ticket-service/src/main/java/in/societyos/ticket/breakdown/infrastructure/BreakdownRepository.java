package in.societyos.ticket.breakdown.infrastructure;

import in.societyos.ticket.breakdown.domain.Breakdown;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreakdownRepository extends JpaRepository<Breakdown, UUID> {
  List<Breakdown> findAllByOrderByReportedAtDesc();
  List<Breakdown> findByStatusOrderByReportedAtDesc(Breakdown.Status status);
  boolean existsBySourceRef(String sourceRef);
}
