package in.societyos.billing.payment.infrastructure;

import in.societyos.billing.payment.domain.PaymentAllocation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {

  List<PaymentAllocation> findByPaymentId(UUID paymentId);

  List<PaymentAllocation> findByBillId(UUID billId);

  @Query("select coalesce(sum(a.amountPaise), 0) from PaymentAllocation a where a.paymentId = :paymentId")
  long allocatedOf(UUID paymentId);

  List<PaymentAllocation> findByPaymentIdIn(Collection<UUID> paymentIds);
}
