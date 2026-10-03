package in.societyos.billing.payment.infrastructure;

import in.societyos.billing.payment.domain.Payment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.gatewayOrderId = :orderId")
  Optional<Payment> lockByOrderId(String orderId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.id = :id")
  Optional<Payment> lockById(UUID id);

  List<Payment> findByFlatIdInOrderByCreatedAtDesc(Collection<UUID> flatIds, Limit limit);

  List<Payment> findAllByOrderByCreatedAtDesc(Limit limit);

  @Query("select p from Payment p where p.status = 'PENDING' and p.method = 'ONLINE' and p.createdAt <= :before")
  List<Payment> pendingOnlineBefore(Instant before);

  /** Succeeded payments of a flat, oldest first (advance application order). */
  @Query("select p from Payment p where p.flatId = :flatId and p.status = 'SUCCEEDED' order by p.paidAt asc, p.id asc")
  List<Payment> succeededOf(UUID flatId);
}
