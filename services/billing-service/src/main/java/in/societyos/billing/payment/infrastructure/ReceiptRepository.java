package in.societyos.billing.payment.infrastructure;

import in.societyos.billing.payment.domain.Receipt;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

  Optional<Receipt> findByPaymentId(UUID paymentId);

  List<Receipt> findByFlatIdInOrderByIssuedAtDesc(Collection<UUID> flatIds, Limit limit);

  List<Receipt> findAllByOrderByIssuedAtDesc(Limit limit);
}
