package in.societyos.billing.charge.infrastructure;

import in.societyos.billing.charge.domain.PendingCharge;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingChargeRepository extends JpaRepository<PendingCharge, UUID> {

  List<PendingCharge> findByStatusOrderByCreatedAtAsc(String status);

  List<PendingCharge> findByFlatIdOrderByCreatedAtDesc(UUID flatId);

  List<PendingCharge> findTop200ByOrderByCreatedAtDesc();

  List<PendingCharge> findByIdIn(Collection<UUID> ids);

  Optional<PendingCharge> findBySourceTypeAndSourceRef(String sourceType, UUID sourceRef);
}
