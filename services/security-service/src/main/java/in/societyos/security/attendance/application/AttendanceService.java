package in.societyos.security.attendance.application;

import in.societyos.security.attendance.domain.StaffAttendance;
import in.societyos.security.attendance.infrastructure.StaffAttendanceRepository;
import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.directory.application.GateAccess;
import in.societyos.security.directory.domain.DomesticStaff;
import in.societyos.security.entry.application.EntryService;
import in.societyos.security.entry.domain.EntryLog;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domestic staff at the gate, found by id or by enrolled phone in the society copy. Each visit
 * is an attendance row plus a STAFF entry in the gate log (so residents get "Sunita arrived").
 */
@Service
public class AttendanceService {

  public record AttendanceView(StaffAttendance attendance, DomesticStaff staff) {}

  private final StaffAttendanceRepository attendance;
  private final DirectoryService directory;
  private final EntryService entries;
  private final GateAccess access;
  private final Clock clock;

  public AttendanceService(StaffAttendanceRepository attendance, DirectoryService directory, EntryService entries,
      GateAccess access, Clock clock) {
    this.attendance = attendance;
    this.directory = directory;
    this.entries = entries;
    this.access = access;
    this.clock = clock;
  }

  @Transactional
  public AttendanceView checkIn(UUID staffId, String phone, UUID gateId) {
    DomesticStaff staff = resolve(staffId, phone);
    StaffAttendance.requireAllowed(staff.getStatus());
    if (attendance.findBySocietyIdAndStaffIdAndOutAtIsNull(society(), staff.getId()).isPresent()) {
      throw ProblemException.conflict("STAFF_ALREADY_INSIDE", staff.getName() + " is already checked in");
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    UUID entryId = null;
    if (!staff.getFlatIds().isEmpty()) {
      EntryLog entry = entries.admitStaff(staff.getId(), staff.getName(), staff.getFlatIds(), gateId, now);
      entryId = entry.getId();
    }
    StaffAttendance a = attendance.save(new StaffAttendance(staff.getId(), gateId, now, access.userId(), entryId));
    return new AttendanceView(a, staff);
  }

  @Transactional
  public AttendanceView checkOut(UUID staffId, String phone) {
    DomesticStaff staff = resolve(staffId, phone);
    StaffAttendance a = attendance.findBySocietyIdAndStaffIdAndOutAtIsNull(society(), staff.getId())
        .orElseThrow(() -> ProblemException.unprocessable("STAFF_NOT_INSIDE", staff.getName() + " is not checked in"));
    Instant now = clock.instant();
    a.checkOut(now);
    attendance.save(a);
    if (a.getEntryId() != null) {
      entries.closeStaffEntry(a.getEntryId(), now);
    }
    return new AttendanceView(a, staff);
  }

  /** Society-wide for guards and managers; a resident passes their flat to see their own staff. */
  @Transactional(readOnly = true)
  public List<AttendanceView> list(Instant from, Instant to, UUID flatId, boolean insideOnly) {
    Instant end = to == null ? clock.instant().plus(1, ChronoUnit.DAYS) : to;
    Instant start = from == null ? end.minus(7, ChronoUnit.DAYS) : from;
    List<StaffAttendance> rows;
    if (flatId != null) {
      access.requireFlatView(flatId);
      List<UUID> staffIds = directory.flatCard(flatId).staff().stream().map(DomesticStaff::getId).toList();
      rows = staffIds.isEmpty() ? List.of()
          : attendance.findBySocietyIdAndStaffIdInAndInAtGreaterThanEqualAndInAtLessThanOrderByInAtDesc(
              society(), staffIds, start, end, Limit.of(500));
    } else if (access.canSeeSocietyLog() || access.canRecordAttendance()) {
      rows = insideOnly ? attendance.findBySocietyIdAndOutAtIsNullOrderByInAtAsc(society())
          : attendance.findBySocietyIdAndInAtGreaterThanEqualAndInAtLessThanOrderByInAtDesc(society(), start, end,
              Limit.of(500));
    } else {
      throw ProblemException.forbidden("NOT_ALLOWED", "Pass a flatId to see your own staff");
    }
    Map<UUID, DomesticStaff> staff = directory.staffByIds(rows.stream().map(StaffAttendance::getStaffId).toList());
    return rows.stream().map(a -> new AttendanceView(a, staff.get(a.getStaffId()))).toList();
  }

  @Transactional
  public int purgeBefore(Instant before) {
    return attendance.purge(society(), before);
  }

  private DomesticStaff resolve(UUID staffId, String phone) {
    if (staffId != null) {
      return directory.requireStaff(staffId);
    }
    if (phone != null && !phone.isBlank()) {
      return directory.staffByPhone(phone).orElseThrow(() -> ProblemException.notFound("staff", "with this phone"));
    }
    throw ProblemException.badRequest("STAFF_REQUIRED", "Pass staffId or phone");
  }

  private static UUID society() {
    return TenantContext.activeSocietyId();
  }
}
