package in.societyos.community.event.domain;

import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A resident's RSVP (one per event and user; headcount includes family and guests). */
@Entity
@Table(name = "event_registration")
public class EventRegistration extends TenantEntity {

  @Column(name = "event_id", nullable = false) private UUID eventId;
  @Column(name = "flat_id", nullable = false) private UUID flatId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(nullable = false) private int headcount;
  @Column(nullable = false) private String status;

  protected EventRegistration() {}

  public EventRegistration(UUID eventId, UUID flatId, UUID userId) {
    this.eventId = eventId;
    this.flatId = flatId;
    this.userId = userId;
    this.headcount = 1;
    this.status = "GOING";
  }

  public void respond(UUID flatId, boolean going, int headcount) {
    this.flatId = flatId;
    this.status = going ? "GOING" : "NOT_GOING";
    this.headcount = Math.max(1, headcount);
  }

  public boolean isGoing() { return "GOING".equals(status); }
  public UUID getEventId() { return eventId; }
  public UUID getFlatId() { return flatId; }
  public UUID getUserId() { return userId; }
  public int getHeadcount() { return headcount; }
  public String getStatus() { return status; }
}
