package in.societyos.security.delivery.infrastructure;

import in.societyos.security.delivery.domain.Delivery;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

  List<Delivery> findBySocietyIdAndFlatIdInOrderByReceivedAtDesc(UUID societyId, Collection<UUID> flatIds, Limit limit);

  List<Delivery> findBySocietyIdAndStatusOrderByReceivedAtDesc(UUID societyId, String status, Limit limit);

  List<Delivery> findBySocietyIdOrderByReceivedAtDesc(UUID societyId, Limit limit);

  @Modifying
  @org.springframework.transaction.annotation.Transactional
  @Query("delete from Delivery d where d.societyId = :societyId and d.receivedAt < :before")
  int purge(@Param("societyId") UUID societyId, @Param("before") Instant before);
}
