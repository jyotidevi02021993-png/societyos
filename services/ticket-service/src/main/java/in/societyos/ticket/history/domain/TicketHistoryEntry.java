package in.societyos.ticket.history.domain;

import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One line of a ticket's timeline (table {@code ticket_event}). */
@Entity
@Table(name = "ticket_event")
public class TicketHistoryEntry extends TenantEntity {

  @Column(name = "ticket_type", nullable = false, updatable = false)
  private String ticketType;
  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;
  @Column(nullable = false, updatable = false)
  private Instant at;
  @Column(name = "actor_id", updatable = false)
  private UUID actorId;
  @Column(name = "from_status", updatable = false)
  private String fromStatus;
  @Column(name = "to_status", updatable = false)
  private String toStatus;
  @Column(updatable = false, columnDefinition = "text")
  private String note;

  protected TicketHistoryEntry() {}

  public TicketHistoryEntry(String ticketType, UUID ticketId, UUID actorId, String fromStatus, String toStatus,
      String note) {
    this.ticketType = ticketType;
    this.ticketId = ticketId;
    this.at = Instant.now();
    this.actorId = actorId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.note = note;
  }

  public String getTicketType() { return ticketType; }
  public UUID getTicketId() { return ticketId; }
  public Instant getAt() { return at; }
  public UUID getActorId() { return actorId; }
  public String getFromStatus() { return fromStatus; }
  public String getToStatus() { return toStatus; }
  public String getNote() { return note; }
}
