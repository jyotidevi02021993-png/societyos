package in.societyos.notification.intake.infrastructure;

import in.societyos.notification.intake.domain.NotificationRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, UUID> {

  /** Returns 0 when the society already has a request with this dedupe key. */
  @Modifying
  @Query(value = """
      insert into notification_request (id, society_id, dedupe_key, source_event_id, source_type, category, template,
        priority, recipients, created_at, updated_at, version)
      values (:id, :society, :key, :eventId, :type, :category, :template, :priority, :recipients, now(), now(), 0)
      on conflict (society_id, dedupe_key) do nothing""", nativeQuery = true)
  int insertIfNew(@Param("id") UUID id, @Param("society") UUID society, @Param("key") String dedupeKey,
      @Param("eventId") UUID eventId, @Param("type") String sourceType, @Param("category") String category,
      @Param("template") String template, @Param("priority") String priority, @Param("recipients") int recipients);

  /** Retention: deletes requests (and, by cascade, notifications, deliveries, attempts) older than {@code before}. */
  @Modifying
  @Query(value = "delete from notification_request where society_id = :society and created_at < :before",
      nativeQuery = true)
  int purgeBefore(@Param("society") UUID society, @Param("before") Instant before);

  @Query(value = """
      select count(*) from notification n join notification_request r on r.id = n.request_id
      where r.society_id = :society and r.created_at < :before""", nativeQuery = true)
  long countNotificationsBefore(@Param("society") UUID society, @Param("before") Instant before);
}
