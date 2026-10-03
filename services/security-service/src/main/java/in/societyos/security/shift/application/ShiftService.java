package in.societyos.security.shift.application;

import in.societyos.security.directory.application.GateAccess;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.shift.domain.GuardShift;
import in.societyos.security.shift.infrastructure.GuardShiftRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Guard roster: managers plan shifts, guards check in and out of their own. */
@Service
public class ShiftService {

  private final GuardShiftRepository shifts;
  private final GateAccess access;
  private final Clock clock;

  public ShiftService(GuardShiftRepository shifts, GateAccess access, Clock clock) {
    this.shifts = shifts;
    this.access = access;
    this.clock = clock;
  }

  @Transactional
  public GuardShift plan(UUID guardUserId, UUID gateId, Instant startsAt, Instant endsAt) {
    GuardShift shift = new GuardShift(guardUserId, gateId, startsAt, endsAt);
    boolean clash = shifts.findBySocietyIdAndGuardUserIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
        society(), guardUserId, endsAt, startsAt).stream().findAny().isPresent();
    if (clash) {
      throw ProblemException.conflict("SHIFT_OVERLAP", "This guard already has a shift in that window");
    }
    return shifts.save(shift);
  }

  @Transactional(readOnly = true)
  public List<GuardShift> list(Instant from, Instant to, boolean mine) {
    Instant start = from == null ? clock.instant().truncatedTo(ChronoUnit.DAYS) : from;
    Instant end = to == null ? start.plus(7, ChronoUnit.DAYS) : to;
    if (mine) {
      return shifts.findBySocietyIdAndGuardUserIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
          society(), access.userId(), end, start);
    }
    return shifts.findBySocietyIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(society(), end, start);
  }

  @Transactional
  public GuardShift checkIn(UUID id) {
    GuardShift s = require(id);
    s.checkIn(access.userId(), clock.instant());
    return shifts.save(s);
  }

  @Transactional
  public GuardShift checkOut(UUID id) {
    GuardShift s = require(id);
    s.checkOut(access.userId(), clock.instant());
    return shifts.save(s);
  }

  private GuardShift require(UUID id) {
    return shifts.findById(id).orElseThrow(() -> ProblemException.notFound("shift", id));
  }

  private static UUID society() {
    return TenantContext.activeSocietyId();
  }
}
