package in.societyos.notification.delivery.infrastructure;

import in.societyos.notification.delivery.domain.DeliveryAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

  List<DeliveryAttempt> findByDeliveryIdOrderByAttemptNoAsc(UUID deliveryId);
}
