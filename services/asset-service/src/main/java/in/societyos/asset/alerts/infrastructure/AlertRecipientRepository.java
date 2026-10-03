package in.societyos.asset.alerts.infrastructure;

import in.societyos.asset.alerts.domain.AlertRecipient;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRecipientRepository extends JpaRepository<AlertRecipient, UUID> {
  List<AlertRecipient> findAllByOrderByCreatedAtAsc();
}
