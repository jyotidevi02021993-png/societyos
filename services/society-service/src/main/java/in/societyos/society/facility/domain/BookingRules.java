package in.societyos.society.facility.domain;

import in.societyos.society.platform.core.error.ProblemException;

/**
 * How a facility may be booked (community-service enforces them on each booking).
 *
 * @param slotMinutes length of one booking slot
 * @param maxAdvanceDays how far ahead a flat may book
 * @param maxPerFlatPerWeek bookings one flat may hold in a week; 0 means no limit
 */
public record BookingRules(Integer slotMinutes, Integer maxAdvanceDays, Integer maxPerFlatPerWeek) {

  public static BookingRules defaults() {
    return new BookingRules(60, 14, 0);
  }

  /** Missing fields take the defaults; every value is range-checked. */
  public BookingRules validated() {
    BookingRules d = defaults();
    BookingRules r = new BookingRules(
        slotMinutes != null ? slotMinutes : d.slotMinutes,
        maxAdvanceDays != null ? maxAdvanceDays : d.maxAdvanceDays,
        maxPerFlatPerWeek != null ? maxPerFlatPerWeek : d.maxPerFlatPerWeek);
    range("slotMinutes", r.slotMinutes, 15, 1440);
    range("maxAdvanceDays", r.maxAdvanceDays, 0, 365);
    range("maxPerFlatPerWeek", r.maxPerFlatPerWeek, 0, 100);
    if (1440 % r.slotMinutes != 0) {
      throw ProblemException.badRequest("INVALID_BOOKING_RULES", "slotMinutes must divide a day evenly");
    }
    return r;
  }

  private static void range(String field, int value, int min, int max) {
    if (value < min || value > max) {
      throw ProblemException.badRequest(
          "INVALID_BOOKING_RULES", "%s must be between %d and %d".formatted(field, min, max));
    }
  }
}
