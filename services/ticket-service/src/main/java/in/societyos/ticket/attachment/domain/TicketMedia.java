package in.societyos.ticket.attachment.domain;

import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A photo (media-service id) attached to a complaint or breakdown. */
@Entity
@Table(name = "ticket_media")
public class TicketMedia extends TenantEntity {

  @Column(name = "ticket_type", nullable = false, updatable = false)
  private String ticketType;
  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;
  @Column(name = "media_id", nullable = false, updatable = false)
  private UUID mediaId;

  protected TicketMedia() {}

  public TicketMedia(String ticketType, UUID ticketId, UUID mediaId) {
    this.ticketType = ticketType;
    this.ticketId = ticketId;
    this.mediaId = mediaId;
  }

  public String getTicketType() { return ticketType; }
  public UUID getTicketId() { return ticketId; }
  public UUID getMediaId() { return mediaId; }
}
