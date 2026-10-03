package in.societyos.billing.bill.infrastructure;

import in.societyos.billing.bill.domain.BillRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BillRunRepository extends JpaRepository<BillRun, UUID> {

  @Query("select r from BillRun r where r.period = :period and r.status in ('PREVIEW', 'PUBLISHED')")
  Optional<BillRun> liveFor(String period);

  List<BillRun> findTop36ByOrderByPeriodDescCreatedAtDesc();
}
