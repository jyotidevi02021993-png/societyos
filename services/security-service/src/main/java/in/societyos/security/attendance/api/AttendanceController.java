package in.societyos.security.attendance.api;

import in.societyos.security.attendance.application.AttendanceService;
import in.societyos.security.attendance.application.AttendanceService.AttendanceView;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/staff-attendance")
class AttendanceController {

  private final AttendanceService attendance;

  AttendanceController(AttendanceService attendance) {
    this.attendance = attendance;
  }

  /** Identify the staff member by id (from the directory) or by enrolled phone. */
  record StaffAtGate(UUID staffId, @Size(max = 20) String phone, UUID gateId) {}

  record AttendanceResponse(UUID id, UUID staffId, String staffName, String staffKind, List<UUID> flatIds,
      UUID gateId, Instant inAt, Instant outAt, UUID entryId) {
    static AttendanceResponse from(AttendanceView v) {
      var a = v.attendance();
      var s = v.staff();
      return new AttendanceResponse(a.getId(), a.getStaffId(), s == null ? null : s.getName(),
          s == null ? null : s.getKind(), s == null ? List.of() : s.getFlatIds(), a.getGateId(), a.getInAt(),
          a.getOutAt(), a.getEntryId());
    }
  }

  @PostMapping("/check-in")
  @PreAuthorize("@perm.hasAny('staff:attendance', 'gate:entry')")
  AttendanceResponse checkIn(@RequestBody StaffAtGate r) {
    return AttendanceResponse.from(attendance.checkIn(r.staffId(), r.phone(), r.gateId()));
  }

  @PostMapping("/check-out")
  @PreAuthorize("@perm.hasAny('staff:attendance', 'gate:entry')")
  AttendanceResponse checkOut(@RequestBody StaffAtGate r) {
    return AttendanceResponse.from(attendance.checkOut(r.staffId(), r.phone()));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('staff:attendance', 'gate:entry', 'gate:log-view', 'gatepass:view')")
  List<AttendanceResponse> list(@RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to, @RequestParam(required = false) UUID flatId,
      @RequestParam(defaultValue = "false") boolean inside) {
    return attendance.list(from, to, flatId, inside).stream().map(AttendanceResponse::from).toList();
  }
}
