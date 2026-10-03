package in.societyos.community.booking.domain;

import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A facility booking. CONFIRMED on creation (after {@link BookingPolicy}); a chargeable booking's
 * charge goes on the flat's next bill through {@code community.booking.confirmed}.
 */
@Entity
@Table(name = "booking")
public class Booking extends TenantEntity {

  @Column(name = "facility_id", nullable = false) private UUID facilityId;
  @Column(name = "flat_id", nullable = false) private UUID flatId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(name = "starts_at", nullable = false) private Instant startsAt;
  @Column(name = "ends_at", nullable = false) private Instant endsAt;
  @Column(nullable = false) private int guests;
  @Column(nullable = false) private boolean exclusive;
  @Column(nullable = false) private String status;
  @Column(name = "charge_paise", nullable = false) private long chargePaise;
  @Column(name = "cancelled_at") private Instant cancelledAt;
  @Column(name = "cancel_reason") private String cancelReason;

  protected Booking() {}

  public Booking(UUID facilityId, UUID flatId, UUID userId, Instant startsAt, Instant endsAt, int guests,
      boolean exclusive, long chargePaise) {
    this.facilityId = facilityId;
    this.flatId = flatId;
    this.userId = userId;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
    this.guests = guests;
    this.exclusive = exclusive;
    this.chargePaise = chargePaise;
    this.status = "CONFIRMED";
  }

  /** Residents cancel before the start; managers until the end. */
  public void cancel(Instant now, String reason, boolean manager) {
    if (!"CONFIRMED".equals(status)) {
      throw ProblemException.unprocessable("BOOKING_CANCELLED", "The booking is already cancelled");
    }
    if (!(manager ? endsAt : startsAt).isAfter(now)) {
      throw ProblemException.unprocessable("BOOKING_STARTED", "A booking that has started cannot be cancelled");
    }
    status = "CANCELLED";
    cancelledAt = now;
    cancelReason = reason;
  }

  public BookingPolicy.Existing asExisting() {
    return new BookingPolicy.Existing(startsAt, endsAt, guests);
  }

  public UUID getFacilityId() { return facilityId; }
  public UUID getFlatId() { return flatId; }
  public UUID getUserId() { return userId; }
  public Instant getStartsAt() { return startsAt; }
  public Instant getEndsAt() { return endsAt; }
  public int getGuests() { return guests; }
  public boolean isExclusive() { return exclusive; }
  public String getStatus() { return status; }
  public long getChargePaise() { return chargePaise; }
  public Instant getCancelledAt() { return cancelledAt; }
  public String getCancelReason() { return cancelReason; }
}
