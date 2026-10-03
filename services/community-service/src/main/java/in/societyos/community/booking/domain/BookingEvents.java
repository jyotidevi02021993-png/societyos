package in.societyos.community.booking.domain;

import in.societyos.community.common.CommunityDomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code community.booking.requested/confirmed/cancelled} (catalogue: bookingId, facilityId, flatId,
 * userId, startsAt, endsAt, chargePaise). billing-service bills {@code chargePaise} on confirmed and
 * drops a not-yet-billed charge on cancelled.
 */
public final class BookingEvents {

  private BookingEvents() {}

  public record BookingRequested(UUID bookingId, UUID facilityId, UUID flatId, UUID userId, Instant startsAt,
      Instant endsAt, long chargePaise) implements CommunityDomainEvent {
    @Override public String type() { return "community.booking.requested"; }
    @Override public UUID aggregateId() { return bookingId; }
  }

  public record BookingConfirmed(UUID bookingId, UUID facilityId, UUID flatId, UUID userId, Instant startsAt,
      Instant endsAt, long chargePaise) implements CommunityDomainEvent {
    @Override public String type() { return "community.booking.confirmed"; }
    @Override public UUID aggregateId() { return bookingId; }
  }

  public record BookingCancelled(UUID bookingId, UUID facilityId, UUID flatId, UUID userId, Instant startsAt,
      Instant endsAt, long chargePaise) implements CommunityDomainEvent {
    @Override public String type() { return "community.booking.cancelled"; }
    @Override public UUID aggregateId() { return bookingId; }
  }

  public static BookingRequested requested(Booking b) {
    return new BookingRequested(b.getId(), b.getFacilityId(), b.getFlatId(), b.getUserId(), b.getStartsAt(),
        b.getEndsAt(), b.getChargePaise());
  }

  public static BookingConfirmed confirmed(Booking b) {
    return new BookingConfirmed(b.getId(), b.getFacilityId(), b.getFlatId(), b.getUserId(), b.getStartsAt(),
        b.getEndsAt(), b.getChargePaise());
  }

  public static BookingCancelled cancelled(Booking b) {
    return new BookingCancelled(b.getId(), b.getFacilityId(), b.getFlatId(), b.getUserId(), b.getStartsAt(),
        b.getEndsAt(), b.getChargePaise());
  }
}
