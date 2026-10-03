package in.societyos.billing.bill.infrastructure;

import in.societyos.billing.bill.domain.BillAdjustment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillAdjustmentRepository extends JpaRepository<BillAdjustment, UUID> {

  List<BillAdjustment> findByBillIdOrderByCreatedAtAsc(UUID billId);
}
