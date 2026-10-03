package in.societyos.society.facility.domain;

import in.societyos.society.common.SocietyEvent;
import java.util.UUID;

/** {@code society.facility.created} / {@code society.facility.updated}: community-service keeps a copy for bookings. */
public final class FacilityEvents {

  private FacilityEvents() {}

  public record FacilityCreated(UUID facilityId, String kind, String name, int capacity, boolean chargeable,
      long chargePaise, BookingRules bookingRules, String status) implements SocietyEvent {
    @Override public String type() { return "society.facility.created"; }
    @Override public UUID aggregateId() { return facilityId; }
  }

  public record FacilityUpdated(UUID facilityId, String kind, String name, int capacity, boolean chargeable,
      long chargePaise, BookingRules bookingRules, String status) implements SocietyEvent {
    @Override public String type() { return "society.facility.updated"; }
    @Override public UUID aggregateId() { return facilityId; }
  }
}
