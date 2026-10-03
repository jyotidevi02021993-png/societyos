package in.societyos.ticket.complaint.domain;

import in.societyos.ticket.common.TicketEvent;
import in.societyos.ticket.common.Texts;
import java.time.Instant;
import java.util.UUID;

/** Complaint events, exactly as in contracts/events/CATALOGUE.md (ticket section). */
public final class ComplaintEvents {

  private ComplaintEvents() {}

  /** {@code text} is the resident's text with phone numbers and e-mails removed. */
  public record ComplaintCreated(UUID complaintId, String number, UUID flatId, UUID locationId, UUID assetId,
      UUID categoryId, String categoryName, String priority, UUID raisedBy, String text) implements TicketEvent {
    @Override public String type() { return "ticket.complaint.created"; }
    @Override public UUID aggregateId() { return complaintId; }
  }

  public record ComplaintResolved(UUID complaintId, String number, Instant at) implements TicketEvent {
    @Override public String type() { return "ticket.complaint.resolved"; }
    @Override public UUID aggregateId() { return complaintId; }
  }

  public record ComplaintReopened(UUID complaintId, String number, Instant at) implements TicketEvent {
    @Override public String type() { return "ticket.complaint.reopened"; }
    @Override public UUID aggregateId() { return complaintId; }
  }

  public static ComplaintCreated created(Complaint c) {
    return new ComplaintCreated(c.getId(), c.getNumber(), c.getFlatId(), c.getLocationId(), c.getAssetId(),
        c.getCategoryId(), c.getCategoryName(), c.getPriority(), c.getRaisedBy(), Texts.redactContact(c.getDescription()));
  }

  public static ComplaintResolved resolved(Complaint c, Instant at) {
    return new ComplaintResolved(c.getId(), c.getNumber(), at);
  }

  public static ComplaintReopened reopened(Complaint c, Instant at) {
    return new ComplaintReopened(c.getId(), c.getNumber(), at);
  }
}
