package in.societyos.ticket.history.application;

import in.societyos.ticket.history.domain.TicketHistoryEntry;
import in.societyos.ticket.history.infrastructure.TicketHistoryRepository;
import in.societyos.ticket.platform.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends to and reads the timeline of complaints, breakdowns and job cards. */
@Service
public class TicketHistory {

  public static final String COMPLAINT = "COMPLAINT";
  public static final String BREAKDOWN = "BREAKDOWN";
  public static final String JOBCARD = "JOBCARD";

  private final TicketHistoryRepository entries;

  public TicketHistory(TicketHistoryRepository entries) {
    this.entries = entries;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(String ticketType, UUID ticketId, String from, String to, String note) {
    entries.save(new TicketHistoryEntry(ticketType, ticketId, TenantContext.userId().orElse(null), from, to, note));
  }

  @Transactional(readOnly = true)
  public List<TicketHistoryEntry> of(String ticketType, UUID ticketId) {
    return entries.findByTicketTypeAndTicketIdOrderByAtAscIdAsc(ticketType, ticketId);
  }
}
