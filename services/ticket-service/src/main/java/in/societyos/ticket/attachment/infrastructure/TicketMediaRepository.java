package in.societyos.ticket.attachment.infrastructure;

import in.societyos.ticket.attachment.domain.TicketMedia;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketMediaRepository extends JpaRepository<TicketMedia, UUID> {
  List<TicketMedia> findByTicketTypeAndTicketIdOrderByCreatedAtAsc(String ticketType, UUID ticketId);
}
