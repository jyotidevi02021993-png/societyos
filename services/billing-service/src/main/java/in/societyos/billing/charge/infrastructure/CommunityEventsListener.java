package in.societyos.billing.charge.infrastructure;

import in.societyos.billing.charge.application.PendingChargeService;
import in.societyos.billing.platform.events.CloudEvent;
import in.societyos.billing.platform.events.DomainEventListener;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Group {@code billing.booking-charges} on {@code sos.community.events.v1}; DLQ
 * {@code sos.dlq.billing.booking-charges}. Chargeable facility bookings become pending charges.
 */
@Component
class CommunityEventsListener {

  static final String TOPIC = "sos.community.events.v1";
  static final String GROUP = "billing.booking-charges";

  record BookingData(UUID bookingId, UUID facilityId, UUID flatId, UUID userId, Instant startsAt, Instant endsAt,
      Long chargePaise) {
    PendingChargeService.Booking booking() {
      return new PendingChargeService.Booking(bookingId, facilityId, flatId, startsAt,
          chargePaise == null ? 0 : chargePaise);
    }
  }

  private final PendingChargeService charges;

  CommunityEventsListener(PendingChargeService charges) {
    this.charges = charges;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "community.booking.confirmed")
  void onConfirmed(CloudEvent<BookingData> e) {
    charges.bookingConfirmed(e.data().booking());
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "community.booking.cancelled")
  void onCancelled(CloudEvent<BookingData> e) {
    charges.bookingCancelled(e.data().booking());
  }
}
