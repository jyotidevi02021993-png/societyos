package in.societyos.vendor.purchasing.infrastructure;

import in.societyos.vendor.purchasing.domain.PurchaseOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PurchaseOrder p where p.id = :id")
  Optional<PurchaseOrder> findForUpdate(UUID id);

  List<PurchaseOrder> findTop200ByOrderByCreatedAtDesc();

  List<PurchaseOrder> findTop200ByStatusOrderByCreatedAtDesc(PurchaseOrder.Status status);

  List<PurchaseOrder> findTop200ByVendorIdOrderByCreatedAtDesc(UUID vendorId);

  List<PurchaseOrder> findTop200ByVendorIdAndStatusNotOrderByCreatedAtDesc(UUID vendorId, PurchaseOrder.Status status);
}
