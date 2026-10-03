package in.societyos.community.event.domain;

import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;

/** A community event (festival, sports day, blood donation, AGM...) residents RSVP to. */
@Entity
@Table(name = "community_event")
public class CommunityEvent extends TenantEntity {

  public static final Set<String> KINDS = Set.of("FESTIVAL", "SPORTS", "BLOOD_DONATION", "PLANTATION",
      "CLEANLINESS_DRIVE", "AGM", "MEETING", "CULTURAL", "OTHER");

  public record Details(String kind, String title, String description, String location, Instant startsAt,
      Instant endsAt, Integer capacity, long feePaise) {}

  @Column(nullable = false) private String kind;
  @Column(nullable = false) private String title;
  private String description;
  private String location;
  @Column(name = "starts_at", nullable = false) private Instant startsAt;
  @Column(name = "ends_at", nullable = false) private Instant endsAt;
  private Integer capacity;
  @Column(name = "fee_paise", nullable = false) private long feePaise;
  @Column(nullable = false) private String status;

  protected CommunityEvent() {}

  public CommunityEvent(Details d) {
    if (!KINDS.contains(d.kind())) {
      throw ProblemException.badRequest("INVALID_EVENT_KIND", "kind must be one of " + KINDS);
    }
    if (!d.endsAt().isAfter(d.startsAt())) {
      throw ProblemException.badRequest("INVALID_TIMES", "endsAt must be after startsAt");
    }
    if (d.feePaise() < 0 || (d.capacity() != null && d.capacity() < 1)) {
      throw ProblemException.badRequest("INVALID_EVENT", "capacity must be positive and fee not negative");
    }
    this.kind = d.kind();
    this.title = d.title();
    this.description = d.description();
    this.location = d.location();
    this.startsAt = d.startsAt();
    this.endsAt = d.endsAt();
    this.capacity = d.capacity();
    this.feePaise = d.feePaise();
    this.status = "SCHEDULED";
  }

  public void cancel() {
    if ("CANCELLED".equals(status)) {
      throw ProblemException.unprocessable("EVENT_CANCELLED", "The event is already cancelled");
    }
    status = "CANCELLED";
  }

  /** RSVPs are accepted until the event starts. */
  public void requireOpenForRsvp(Instant now) {
    if (!"SCHEDULED".equals(status) || !startsAt.isAfter(now)) {
      throw ProblemException.unprocessable("RSVP_CLOSED", "RSVP is closed for this event");
    }
  }

  public String getKind() { return kind; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
  public String getLocation() { return location; }
  public Instant getStartsAt() { return startsAt; }
  public Instant getEndsAt() { return endsAt; }
  public Integer getCapacity() { return capacity; }
  public long getFeePaise() { return feePaise; }
  public String getStatus() { return status; }
}
