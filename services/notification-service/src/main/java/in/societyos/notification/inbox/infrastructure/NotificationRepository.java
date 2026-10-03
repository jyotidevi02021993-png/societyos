package in.societyos.notification.inbox.infrastructure;

import in.societyos.notification.inbox.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  Optional<Notification> findByIdAndSocietyIdAndUserId(UUID id, UUID societyId, UUID userId);

  @Query("""
      select n from Notification n where n.societyId = :society and n.userId = :user and n.inApp = true
      and n.createdAt < :before and (:unreadOnly = false or n.readAt is null)
      order by n.createdAt desc""")
  List<Notification> inbox(@Param("society") UUID society, @Param("user") UUID user, @Param("before") Instant before,
      @Param("unreadOnly") boolean unreadOnly, Pageable page);

  long countBySocietyIdAndUserIdAndInAppTrueAndReadAtIsNull(UUID societyId, UUID userId);

  @Modifying
  @Query("""
      update Notification n set n.readAt = :now where n.societyId = :society and n.userId = :user
      and n.inApp = true and n.readAt is null""")
  int markAllRead(@Param("society") UUID society, @Param("user") UUID user, @Param("now") Instant now);

  List<Notification> findByRequestId(UUID requestId);
}
