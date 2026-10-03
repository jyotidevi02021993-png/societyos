package in.societyos.billing.bill.infrastructure;

import in.societyos.billing.bill.domain.Bill;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface BillRepository extends JpaRepository<Bill, UUID>, JpaSpecificationExecutor<Bill> {

  List<Bill> findByBillRunIdOrderByFlatLabelAsc(UUID billRunId);

  /** Open bills of a flat, oldest due first (payment allocation order), locked for update. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Bill b where b.flatId = :flatId and b.status in ('DUE', 'PART_PAID')"
      + " order by b.dueDate asc, b.createdAt asc")
  List<Bill> openForUpdate(UUID flatId);

  @Query("select b from Bill b where b.status in ('DUE', 'PART_PAID') and b.dueDate <= :until order by b.dueDate asc")
  List<Bill> openDueBy(LocalDate until);

  @Query("select b from Bill b where b.status in ('DUE', 'PART_PAID') order by b.flatLabel asc, b.dueDate asc")
  List<Bill> allOpen();

  List<Bill> findByFlatIdInAndStatusNotOrderByDueDateDesc(Collection<UUID> flatIds, String status);

  @Modifying
  @Query("delete from Bill b where b.billRunId = :billRunId and b.status = 'DRAFT'")
  int deleteDrafts(UUID billRunId);
}
