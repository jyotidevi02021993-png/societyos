package in.societyos.billing.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** "Today" for billing is the society's calendar date (sos.default-timezone, IST by default). */
@Component
public class BillingClock {

  private final Clock clock;
  private final ZoneId zone;

  public BillingClock(Clock clock, @Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  public Instant now() {
    return clock.instant();
  }

  public LocalDate today() {
    return LocalDate.now(clock.withZone(zone));
  }

  public LocalDate dateOf(Instant at) {
    return at.atZone(zone).toLocalDate();
  }
}
