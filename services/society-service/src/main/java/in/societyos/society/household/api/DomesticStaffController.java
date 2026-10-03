package in.societyos.society.household.api;

import in.societyos.society.household.application.DomesticStaffService;
import in.societyos.society.household.application.DomesticStaffService.StaffView;
import in.societyos.society.household.domain.DomesticStaff;
import in.societyos.society.platform.core.error.ProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Access per flat is checked in the use case (manager, or owner/tenant of that flat). */
@RestController
@RequestMapping("/v1/domestic-staff")
class DomesticStaffController {

  private final DomesticStaffService staff;

  DomesticStaffController(DomesticStaffService staff) {
    this.staff = staff;
  }

  record RegisterRequest(@NotBlank @Size(max = 120) String name, @NotBlank String kind, @NotBlank String phone,
      UUID photoMediaId, @NotEmpty List<UUID> flatIds) {}

  record UpdateRequest(@NotBlank @Size(max = 120) String name, @NotBlank String kind, UUID photoMediaId,
      String kycStatus, String status) {}

  record StaffResponse(UUID id, String name, String kind, String phoneMasked, UUID photoMediaId, String kycStatus,
      String status, List<UUID> flatIds) {
    static StaffResponse from(StaffView v) {
      DomesticStaff s = v.staff();
      return new StaffResponse(s.getId(), s.getName(), s.getKind(), v.phoneMasked(), s.getPhotoMediaId(),
          s.getKycStatus(), s.getStatus(), s.getFlatIds().stream().sorted().toList());
    }
  }

  @GetMapping
  @PreAuthorize("isAuthenticated()")
  List<StaffResponse> list(@RequestParam(required = false) UUID flatId) {
    return staff.list(flatId).stream().map(StaffResponse::from).toList();
  }

  @GetMapping("/lookup")
  @PreAuthorize("@perm.hasAny('gate:entry', 'member:view', 'member:manage')")
  StaffResponse lookup(@RequestParam String phone) {
    return staff.lookupByPhone(phone).map(StaffResponse::from)
        .orElseThrow(() -> ProblemException.notFound("domestic_staff", "with this phone"));
  }

  @PostMapping
  @PreAuthorize("@perm.hasAny('member:manage', 'household:manage')")
  ResponseEntity<StaffResponse> register(@Valid @RequestBody RegisterRequest r) {
    Set<UUID> flats = new LinkedHashSet<>(r.flatIds());
    StaffView saved = staff.register(
        new DomesticStaffService.NewStaff(r.name(), r.kind(), r.phone(), r.photoMediaId(), flats));
    return ResponseEntity.created(URI.create("/v1/domestic-staff/" + saved.staff().getId()))
        .body(StaffResponse.from(saved));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.hasAny('member:manage', 'household:manage')")
  StaffResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
    return StaffResponse.from(staff.update(id,
        new DomesticStaffService.StaffChange(r.name(), r.kind(), r.photoMediaId(), blankToNull(r.kycStatus()),
            blankToNull(r.status()))));
  }

  @PostMapping("/{id}/flats/{flatId}")
  @PreAuthorize("@perm.hasAny('member:manage', 'household:manage')")
  StaffResponse addFlat(@PathVariable UUID id, @PathVariable UUID flatId) {
    return StaffResponse.from(staff.addFlat(id, flatId));
  }

  @DeleteMapping("/{id}/flats/{flatId}")
  @PreAuthorize("@perm.hasAny('member:manage', 'household:manage')")
  StaffResponse removeFlat(@PathVariable UUID id, @PathVariable UUID flatId) {
    return StaffResponse.from(staff.removeFlat(id, flatId));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
