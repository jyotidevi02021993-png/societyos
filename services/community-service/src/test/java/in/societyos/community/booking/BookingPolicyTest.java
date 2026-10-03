package in.societyos.community.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.community.booking.domain.BookingPolicy;
import in.societyos.community.booking.domain.BookingPolicy.Existing;
import in.societyos.community.booking.domain.BookingPolicy.Facility;
import in.societyos.community.platform.core.error.ProblemException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingPolicyTest {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  /** Monday 2026-10-05 09:00 IST. */
  static final Instant NOW = at(2026, 10, 5, 9, 0);

  static final Facility HALL = new Facility(true, true, 100, 60, 14, 2, true, 50_000);
  static final Facility GYM = new Facility(true, false, 10, 30, 7, 0, false, 0);

  static Instant at(int y, int m, int d, int h, int min) {
    return LocalDateTime.of(y, m, d, h, min).atZone(IST).toInstant();
  }

  static String code(Runnable r) {
    try {
      r.run();
    } catch (ProblemException e) {
      return e.code();
    }
    return "ACCEPTED";
  }

  @Test
  void acceptsAValidBookingAndChargesPerSlot() {
    long charge = BookingPolicy.check(HALL, at(2026, 10, 6, 18, 0), at(2026, 10, 6, 21, 0), 80, NOW, IST, 0, List.of());
    assertThat(charge).isEqualTo(150_000); // 3 slots x Rs 500
    assertThat(BookingPolicy.check(GYM, at(2026, 10, 6, 6, 30), at(2026, 10, 6, 7, 0), 1, NOW, IST, 0, List.of()))
        .isZero();
  }

  @Test
  void rejectsInactivePastTooFarAndMisaligned() {
    Facility inactive = new Facility(false, true, 100, 60, 14, 0, false, 0);
    assertThat(code(() -> BookingPolicy.check(inactive, at(2026, 10, 6, 18, 0), at(2026, 10, 6, 19, 0), 1, NOW, IST, 0,
        List.of()))).isEqualTo("FACILITY_INACTIVE");
    assertThat(code(() -> BookingPolicy.check(HALL, at(2026, 10, 5, 8, 0), at(2026, 10, 5, 9, 0), 1, NOW, IST, 0,
        List.of()))).isEqualTo("BOOKING_IN_PAST");
    assertThat(code(() -> BookingPolicy.check(HALL, at(2026, 10, 20, 18, 0), at(2026, 10, 20, 19, 0), 1, NOW, IST, 0,
        List.of()))).isEqualTo("TOO_FAR_AHEAD");
    assertThat(code(() -> BookingPolicy.check(HALL, at(2026, 10, 19, 18, 0), at(2026, 10, 19, 19, 0), 1, NOW, IST, 0,
        List.of()))).isEqualTo("ACCEPTED"); // exactly 14 days ahead
    assertThat(code(() -> BookingPolicy.check(HALL, at(2026, 10, 6, 18, 30), at(2026, 10, 6, 19, 30), 1, NOW, IST, 0,
        List.of()))).isEqualTo("SLOT_MISALIGNED");
    assertThat(code(() -> BookingPolicy.check(HALL, at(2026, 10, 6, 18, 0), at(2026, 10, 6, 18, 45), 1, NOW, IST, 0,
        List.of()))).isEqualTo("SLOT_MISALIGNED");
    assertThat(code(() -> BookingPolicy.check(HALL, at(2026, 10, 6, 18, 0), at(2026, 10, 6, 18, 0), 1, NOW, IST, 0,
        List.of()))).isEqualTo("INVALID_TIMES");
  }

  @Test
  void enforcesCapacityWeeklyLimitAndOverlap() {
    Instant s = at(2026, 10, 6, 18, 0);
    Instant e = at(2026, 10, 6, 19, 0);
    assertThat(code(() -> BookingPolicy.check(HALL, s, e, 101, NOW, IST, 0, List.of()))).isEqualTo("OVER_CAPACITY");
    assertThat(code(() -> BookingPolicy.check(HALL, s, e, 10, NOW, IST, 2, List.of()))).isEqualTo("WEEKLY_LIMIT_REACHED");
    assertThat(code(() -> BookingPolicy.check(HALL, s, e, 10, NOW, IST, 1, List.of()))).isEqualTo("ACCEPTED");
    assertThat(code(() -> BookingPolicy.check(HALL, s, e, 10, NOW, IST, 0,
        List.of(new Existing(at(2026, 10, 6, 17, 0), at(2026, 10, 6, 19, 0), 5))))).isEqualTo("SLOT_TAKEN");

    // Shared gym: headcount per slot up to capacity
    Instant g1 = at(2026, 10, 6, 6, 0);
    Instant g2 = at(2026, 10, 6, 7, 0);
    List<Existing> gym = List.of(new Existing(g1, at(2026, 10, 6, 6, 30), 6), new Existing(at(2026, 10, 6, 6, 30), g2, 3));
    assertThat(code(() -> BookingPolicy.check(GYM, g1, g2, 4, NOW, IST, 0, gym))).isEqualTo("ACCEPTED");
    assertThat(code(() -> BookingPolicy.check(GYM, g1, g2, 5, NOW, IST, 0, gym))).isEqualTo("SLOT_FULL");
  }

  @Test
  void weekIsMondayToSundayInSocietyTime() {
    BookingPolicy.Week w = BookingPolicy.weekOf(at(2026, 10, 11, 23, 30), IST); // Sunday night
    assertThat(w.start()).isEqualTo(at(2026, 10, 5, 0, 0));
    assertThat(w.end()).isEqualTo(at(2026, 10, 12, 0, 0));
    assertThatThrownBy(() -> BookingPolicy.check(HALL, null, null, 1, NOW, IST, 0, List.of()))
        .isInstanceOf(ProblemException.class);
  }
}
