package in.societyos.community.booking.domain;

import in.societyos.community.platform.core.error.ProblemException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Facility booking rules (society-service {@code bookingRules}), free of persistence:
 *
 * <ul>
 *   <li>the facility is ACTIVE; the booking starts in the future and within {@code maxAdvanceDays};
 *   <li>start and end fall on the {@code slotMinutes} grid of the society's local day;
 *   <li>{@code guests} fits the facility capacity; an exclusive facility (hall, court) takes one
 *       booking per slot, a shared one (gym, pool) takes bookings until the headcount reaches capacity;
 *   <li>a flat holds at most {@code maxPerFlatPerWeek} bookings of the facility per week (Mon–Sun, 0 = no limit).
 * </ul>
 *
 * A chargeable facility costs {@code chargePaise} per slot.
 */
public final class BookingPolicy {

  /** The facility as the rules see it. */
  public record Facility(boolean active, boolean exclusive, int capacity, int slotMinutes, int maxAdvanceDays,
      int maxPerFlatPerWeek, boolean chargeable, long chargePaise) {}

  /** A confirmed booking of the same facility that overlaps the requested time. */
  public record Existing(Instant startsAt, Instant endsAt, int guests) {}

  /** A local week, [start, end). */
  public record Week(Instant start, Instant end) {}

  private BookingPolicy() {}

  /**
   * Checks a request and returns its charge in paise; throws {@link ProblemException} with the
   * rule's code when refused.
   */
  public static long check(Facility f, Instant startsAt, Instant endsAt, int guests, Instant now, ZoneId zone,
      int flatBookingsThatWeek, List<Existing> overlapping) {
    if (!f.active()) {
      throw ProblemException.unprocessable("FACILITY_INACTIVE", "This facility cannot be booked now");
    }
    if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
      throw ProblemException.badRequest("INVALID_TIMES", "endsAt must be after startsAt");
    }
    long minutes = Duration.between(startsAt, endsAt).toMinutes();
    ZonedDateTime localStart = startsAt.atZone(zone);
    long startMinuteOfDay = localStart.getHour() * 60L + localStart.getMinute();
    if (localStart.getSecond() != 0 || localStart.getNano() != 0 || startMinuteOfDay % f.slotMinutes() != 0
        || minutes % f.slotMinutes() != 0 || Duration.between(startsAt, endsAt).toSecondsPart() != 0) {
      throw ProblemException.badRequest("SLOT_MISALIGNED",
          "Bookings use " + f.slotMinutes() + "-minute slots starting on the slot grid");
    }
    if (!startsAt.isAfter(now)) {
      throw ProblemException.unprocessable("BOOKING_IN_PAST", "A booking must start in the future");
    }
    LocalDate lastDay = now.atZone(zone).toLocalDate().plusDays(f.maxAdvanceDays());
    if (localStart.toLocalDate().isAfter(lastDay)) {
      throw ProblemException.unprocessable("TOO_FAR_AHEAD",
          "This facility can be booked at most " + f.maxAdvanceDays() + " day(s) ahead");
    }
    if (guests < 1 || guests > f.capacity()) {
      throw ProblemException.unprocessable("OVER_CAPACITY",
          "Guests must be between 1 and the facility capacity of " + f.capacity());
    }
    if (f.maxPerFlatPerWeek() > 0 && flatBookingsThatWeek >= f.maxPerFlatPerWeek()) {
      throw ProblemException.unprocessable("WEEKLY_LIMIT_REACHED",
          "Your flat already has " + f.maxPerFlatPerWeek() + " booking(s) of this facility that week");
    }
    if (f.exclusive()) {
      if (!overlapping.isEmpty()) {
        throw ProblemException.conflict("SLOT_TAKEN", "That time is already booked");
      }
    } else {
      int peak = peakHeadcount(startsAt, endsAt, f.slotMinutes(), overlapping);
      if (peak + guests > f.capacity()) {
        throw ProblemException.conflict("SLOT_FULL",
            "Only " + Math.max(0, f.capacity() - peak) + " place(s) left at that time");
      }
    }
    return f.chargeable() ? f.chargePaise() * (minutes / f.slotMinutes()) : 0;
  }

  /** Highest headcount already booked in any slot of [start, end). */
  static int peakHeadcount(Instant start, Instant end, int slotMinutes, List<Existing> overlapping) {
    int peak = 0;
    for (Instant slot = start; slot.isBefore(end); slot = slot.plus(Duration.ofMinutes(slotMinutes))) {
      Instant slotEnd = slot.plus(Duration.ofMinutes(slotMinutes));
      final Instant s = slot;
      int used = overlapping.stream()
          .filter(b -> b.startsAt().isBefore(slotEnd) && b.endsAt().isAfter(s))
          .mapToInt(Existing::guests).sum();
      peak = Math.max(peak, used);
    }
    return peak;
  }

  /** The local Monday-to-Sunday week containing {@code at}. */
  public static Week weekOf(Instant at, ZoneId zone) {
    LocalDate monday = at.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return new Week(monday.atStartOfDay(zone).toInstant(), monday.plusWeeks(1).atStartOfDay(zone).toInstant());
  }
}
