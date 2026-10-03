package in.societyos.ticket.breakdown.domain;

import in.societyos.ticket.common.TicketEvent;
import java.util.UUID;

/** {@code ticket.breakdown.reported}, exactly as in the catalogue. */
public record BreakdownReported(UUID breakdownId, String number, UUID assetId, String priority)
    implements TicketEvent {
  @Override public String type() { return "ticket.breakdown.reported"; }
  @Override public UUID aggregateId() { return breakdownId; }
}
