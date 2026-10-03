package in.societyos.security.shift.api;

import in.societyos.security.shift.application.ShiftService;
import in.societyos.security.shift.domain.GuardShift;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guard shifts. Planning needs {@code gate:manage} (proposed) or, until identity grants it,
 * {@code user:manage} which estate managers already hold.
 */
@RestController
@RequestMapping("/v1/shifts")
class ShiftController {

  private final ShiftService shifts;

  ShiftController(ShiftService shifts) {
    this.shifts = shifts;
  }

  record PlanShift(@NotNull UUID guardUserId, UUID gateId, @NotNull Instant startsAt, @NotNull Instant endsAt) {}

  record ShiftResponse(UUID id, UUID guardUserId, UUID gateId, Instant startsAt, Instant endsAt, Instant checkedInAt,
      Instant checkedOutAt) {
    static ShiftResponse from(GuardShift s) {
      return new ShiftResponse(s.getId(), s.getGuardUserId(), s.getGateId(), s.getStartsAt(), s.getEndsAt(),
          s.getCheckedInAt(), s.getCheckedOutAt());
    }
  }

  @PostMapping
  @PreAuthorize("@perm.hasAny('gate:manage', 'user:manage')")
  ResponseEntity<ShiftResponse> plan(@Valid @RequestBody PlanShift r) {
    GuardShift s = shifts.plan(r.guardUserId(), r.gateId(), r.startsAt(), r.endsAt());
    return ResponseEntity.created(URI.create("/v1/shifts/" + s.getId())).body(ShiftResponse.from(s));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('gate:manage', 'user:manage', 'gate:log-view')")
  List<ShiftResponse> list(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
    return shifts.list(from, to, false).stream().map(ShiftResponse::from).toList();
  }

  @GetMapping("/mine")
  @PreAuthorize("@perm.has('gate:entry')")
  List<ShiftResponse> mine(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
    return shifts.list(from, to, true).stream().map(ShiftResponse::from).toList();
  }

  @PostMapping("/{id}/check-in")
  @PreAuthorize("@perm.has('gate:entry')")
  ShiftResponse checkIn(@PathVariable UUID id) {
    return ShiftResponse.from(shifts.checkIn(id));
  }

  @PostMapping("/{id}/check-out")
  @PreAuthorize("@perm.has('gate:entry')")
  ShiftResponse checkOut(@PathVariable UUID id) {
    return ShiftResponse.from(shifts.checkOut(id));
  }
}
