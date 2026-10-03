package in.societyos.ticket.history.infrastructure;

import in.societyos.ticket.history.domain.TicketHistoryEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketHistoryRepository extends JpaRepository<TicketHistoryEntry, UUID> {
  List<TicketHistoryEntry> findByTicketTypeAndTicketIdOrderByAtAscIdAsc(String ticketType, UUID ticketId);
}
