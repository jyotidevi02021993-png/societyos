package in.societyos.community.event.infrastructure;

import in.societyos.community.event.domain.EventRegistration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRegistrationRepository extends JpaRepository<EventRegistration, UUID> {

  Optional<EventRegistration> findByEventIdAndUserId(UUID eventId, UUID userId);

  List<EventRegistration> findByEventIdOrderByCreatedAtAsc(UUID eventId);

  @Query("select coalesce(sum(r.headcount), 0) from EventRegistration r where r.eventId = :event and r.status = 'GOING'")
  long goingHeadcount(@Param("event") UUID eventId);
}
