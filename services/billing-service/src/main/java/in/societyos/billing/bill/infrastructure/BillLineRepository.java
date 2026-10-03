package in.societyos.billing.bill.infrastructure;

import in.societyos.billing.bill.domain.BillLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BillLineRepository extends JpaRepository<BillLine, UUID> {

  List<BillLine> findByBillIdOrderBySortOrderAsc(UUID billId);

  List<BillLine> findByBillIdIn(Collection<UUID> billIds);

  /** Draft lines only; locked lines are protected by the DB trigger anyway. */
  @Modifying
  @Query("delete from BillLine l where l.billId in :billIds and l.lockedAt is null")
  int deleteDrafts(Collection<UUID> billIds);

  @Modifying
  @Query("update BillLine l set l.lockedAt = :at where l.billId in :billIds and l.lockedAt is null")
  int lock(Collection<UUID> billIds, java.time.Instant at);
}
