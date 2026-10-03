package in.societyos.notification.delivery.infrastructure;

import in.societyos.notification.delivery.domain.Delivery;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

  @Query("""
      select d.id from Delivery d where d.societyId = :society and d.status = 'PENDING'
      and d.nextAttemptAt <= :now order by d.nextAttemptAt""")
  List<UUID> dueIds(@Param("society") UUID society, @Param("now") Instant now, Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from Delivery d where d.id = :id")
  Optional<Delivery> lockById(@Param("id") UUID id);

  List<Delivery> findByNotificationIdOrderByChannelAsc(UUID notificationId);
}
