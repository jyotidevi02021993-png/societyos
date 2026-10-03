package in.societyos.security.directory.api;

import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.directory.application.DirectoryService.FlatCard;
import in.societyos.security.directory.domain.DomesticStaff;
import in.societyos.security.directory.domain.FlatDirectoryEntry;
import in.societyos.security.platform.core.error.ProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The guard's view of the society copy: flats, residents (names only), vehicles and staff. Phones masked. */
@RestController
@RequestMapping("/v1/directory")
class DirectoryController {

  private final DirectoryService directory;

  DirectoryController(DirectoryService directory) {
    this.directory = directory;
  }

  record FlatSummary(UUID id, String label, String towerName, String number, Integer floor, String status) {
    static FlatSummary from(FlatDirectoryEntry f) {
      return new FlatSummary(f.getId(), f.getLabel(), f.getTowerName(), f.getNumber(), f.getFloor(), f.getStatus());
    }
  }

  record ResidentItem(String name, String kind, boolean primary, boolean hasApp) {}

  record VehicleItem(UUID id, UUID flatId, String flatLabel, String regNo, String kind, String matchedBy) {}

  record StaffItem(UUID id, String name, String kind, String status, String kycStatus, UUID photoMediaId,
      List<UUID> flatIds, String phoneMasked) {}

  record FlatCardResponse(FlatSummary flat, List<ResidentItem> residents, List<VehicleItem> vehicles,
      List<StaffItem> staff) {}

  record PhoneRequest(@NotBlank String phone) {}

  @GetMapping("/flats")
  @PreAuthorize("@perm.hasAny('gate:entry', 'gate:log-view')")
  List<FlatSummary> searchFlats(@RequestParam String q) {
    return directory.searchFlats(q).stream().map(FlatSummary::from).toList();
  }

  @GetMapping("/flats/{id}")
  @PreAuthorize("@perm.hasAny('gate:entry', 'gate:log-view')")
  FlatCardResponse flat(@PathVariable UUID id) {
    FlatCard card = directory.flatCard(id);
    return new FlatCardResponse(
        FlatSummary.from(card.flat()),
        card.residents().stream().map(r -> new ResidentItem(r.getResidentName(), r.getKind(), r.isPrimary(),
            r.getUserId() != null)).toList(),
        card.vehicles().stream().map(v -> new VehicleItem(v.getId(), v.getFlatId(), card.flat().getLabel(),
            v.getRegNo(), v.getKind(), null)).toList(),
        card.staff().stream().map(this::staffItem).toList());
  }

  @GetMapping("/vehicles/lookup")
  @PreAuthorize("@perm.has('gate:entry')")
  VehicleItem vehicle(@RequestParam(required = false) String regNo, @RequestParam(required = false) String rfidTag) {
    return directory.matchVehicle(regNo, rfidTag)
        .map(m -> new VehicleItem(m.vehicle().getId(), m.vehicle().getFlatId(), m.flatLabel(), m.vehicle().getRegNo(),
            m.vehicle().getKind(), m.matchedBy()))
        .orElseThrow(() -> ProblemException.notFound("vehicle", regNo != null ? regNo : rfidTag));
  }

  /** POST so the phone number never appears in a URL or an access log. */
  @PostMapping("/staff/lookup")
  @PreAuthorize("@perm.hasAny('gate:entry', 'staff:attendance')")
  StaffItem staffByPhone(@Valid @RequestBody PhoneRequest r) {
    return directory.staffByPhone(r.phone()).map(this::staffItem)
        .orElseThrow(() -> ProblemException.notFound("staff", "with this phone"));
  }

  @PutMapping("/staff/{id}/phone")
  @PreAuthorize("@perm.hasAny('gate:entry', 'staff:attendance')")
  StaffItem enrolPhone(@PathVariable UUID id, @Valid @RequestBody PhoneRequest r) {
    return staffItem(directory.enrolStaffPhone(id, r.phone()));
  }

  private StaffItem staffItem(DomesticStaff s) {
    return new StaffItem(s.getId(), s.getName(), s.getKind(), s.getStatus(), s.getKycStatus(), s.getPhotoMediaId(),
        s.getFlatIds(), directory.maskedPhone(s));
  }
}
