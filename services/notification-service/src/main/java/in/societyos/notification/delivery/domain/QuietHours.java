package in.societyos.notification.delivery.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A user's quiet hours in their zone, e.g. 22:00–07:00 (may cross midnight). NORMAL messages on
 * interrupting channels wait until the window ends; HIGH priority (gate approvals, SOS) never waits.
 */
public record QuietHours(LocalTime start, LocalTime end, ZoneId zone) {

  public static final String HIGH = "HIGH";

  /** No window configured. */
  public static QuietHours none(ZoneId zone) {
    return new QuietHours(null, null, zone);
  }

  public boolean isSet() {
    return start != null && end != null && !start.equals(end);
  }

  /** When a message may go out: {@code now}, or the end of the current quiet window. */
  public Instant releaseAt(Instant now, String priority) {
    if (HIGH.equals(priority) || !isSet()) {
      return now;
    }
    ZonedDateTime local = now.atZone(zone);
    LocalTime t = local.toLocalTime();
    LocalDate day = local.toLocalDate();
    boolean overnight = start.isAfter(end);
    if (!overnight) {
      return !t.isBefore(start) && t.isBefore(end) ? day.atTime(end).atZone(zone).toInstant() : now;
    }
    if (!t.isBefore(start)) {
      return day.plusDays(1).atTime(end).atZone(zone).toInstant();
    }
    if (t.isBefore(end)) {
      return day.atTime(end).atZone(zone).toInstant();
    }
    return now;
  }

  public boolean isQuiet(Instant now) {
    return releaseAt(now, "NORMAL").isAfter(now);
  }
}
