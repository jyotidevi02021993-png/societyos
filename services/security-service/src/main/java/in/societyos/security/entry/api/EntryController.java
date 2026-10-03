package in.societyos.security.entry.api;

import in.societyos.security.entry.application.EntryService;
import in.societyos.security.entry.application.EntryService.EdgeEntry;
import in.societyos.security.entry.application.EntryService.EdgeResult;
import in.societyos.security.entry.application.EntryService.EntryView;
import in.societyos.security.entry.application.EntryService.Filter;
import in.societyos.security.entry.application.EntryService.NewEntry;
import in.societyos.security.entry.application.EntryService.PassEntry;
import in.societyos.security.entry.domain.EntryLog;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.web.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** Gate entries: walk-in requests, resident decisions, pass admissions, check-in/out and the log. */
@RestController
@RequestMapping("/v1/entries")
class EntryController {

  private final EntryService entries;

  EntryController(EntryService entries) {
    this.entries = entries;
  }

  record VisitorInput(@NotBlank @Size(max = 120) String name, @Size(max = 20) String phone, UUID photoMediaId) {}

  record RequestEntry(@NotNull UUID flatId, UUID gateId, @NotNull @Valid VisitorInput visitor,
      @NotBlank String purpose, @Size(max = 80) String company, @Size(max = 20) String vehicleReg) {}

  record Decision(@NotBlank @Pattern(regexp = "APPROVE|DENY") String decision) {}

  record PassAdmission(String code, String qrToken, UUID gateId, @Size(max = 120) String visitorName,
      @Size(max = 20) String visitorPhone, UUID photoMediaId, @Size(max = 20) String vehicleReg) {}

  record VisitorOut(UUID id, String name, String phoneMasked, UUID photoMediaId) {}

  record EntryResponse(UUID id, UUID flatId, String flatLabel, UUID gateId, String purpose, String status,
      String source, String visitorName, VisitorOut visitor, UUID staffId, String company, String vehicleReg,
      UUID photoMediaId, UUID passId, Instant requestedAt, Instant expiresAt, UUID decidedBy, Instant decidedAt,
      Instant inAt, Instant outAt, UUID guardId) {
    static EntryResponse from(EntryView v) {
      EntryLog e = v.entry();
      VisitorOut visitor = v.visitor() == null ? null
          : new VisitorOut(v.visitor().id(), v.visitor().name(), v.visitor().phoneMasked(), v.visitor().photoMediaId());
      return new EntryResponse(e.getId(), e.getFlatId(), e.getFlatLabel(), e.getGateId(), e.getPurpose(),
          e.getStatus(), e.getSource(), e.getVisitorName(), visitor, e.getStaffId(), e.getCompany(),
          e.getVehicleReg(), e.getPhotoMediaId(), e.getPassId(), e.getRequestedAt(), e.getExpiresAt(),
          e.getDecidedBy(), e.getDecidedAt(), e.getInAt(), e.getOutAt(), e.getGuardId());
    }
  }

  @PostMapping
  @PreAuthorize("@perm.has('gate:entry')")
  ResponseEntity<EntryResponse> request(@Valid @RequestBody RequestEntry r) {
    EntryView v = entries.request(new NewEntry(r.flatId(), r.gateId(), r.visitor().name(), r.visitor().phone(),
        r.visitor().photoMediaId(), r.purpose(), r.company(), r.vehicleReg()));
    return ResponseEntity.created(URI.create("/v1/entries/" + v.entry().getId())).body(EntryResponse.from(v));
  }

  @PostMapping("/{id}/decision")
  @PreAuthorize("@perm.hasAny('gate:approve', 'gate:entry')")
  EntryResponse decide(@PathVariable UUID id, @Valid @RequestBody Decision d) {
    return EntryResponse.from(entries.decide(id, "APPROVE".equals(d.decision())));
  }

  @PostMapping("/{id}/check-in")
  @PreAuthorize("@perm.has('gate:entry')")
  EntryResponse checkIn(@PathVariable UUID id) {
    return EntryResponse.from(entries.checkIn(id));
  }

  @PostMapping("/{id}/check-out")
  @PreAuthorize("@perm.has('gate:entry')")
  EntryResponse checkOut(@PathVariable UUID id) {
    return EntryResponse.from(entries.checkOut(id));
  }

  /** Pre-approved guest: OTP or QR from a gate pass; checked in straight away. */
  @PostMapping("/pass")
  @PreAuthorize("@perm.has('gate:entry')")
  ResponseEntity<EntryResponse> admitWithPass(@Valid @RequestBody PassAdmission r) {
    if ((r.code() == null || r.code().isBlank()) && (r.qrToken() == null || r.qrToken().isBlank())) {
      throw ProblemException.badRequest("PASS_CODE_REQUIRED", "Pass a 6-digit code or a QR token");
    }
    EntryView v = entries.admitWithPass(new PassEntry(r.code(), r.qrToken(), r.gateId(), r.visitorName(),
        r.visitorPhone(), r.photoMediaId(), r.vehicleReg()));
    return ResponseEntity.created(URI.create("/v1/entries/" + v.entry().getId())).body(EntryResponse.from(v));
  }

  @GetMapping("/pending")
  @PreAuthorize("@perm.has('gate:entry')")
  List<EntryResponse> pending() {
    return entries.pending().stream().map(EntryResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('gate:entry', 'gate:log-view', 'gatepass:view', 'gate:approve')")
  EntryResponse get(@PathVariable UUID id) {
    return EntryResponse.from(entries.get(id));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('gate:entry', 'gate:log-view', 'gatepass:view', 'gate:approve')")
  CursorPage<EntryResponse> list(@RequestParam(required = false) UUID flatId,
      @RequestParam(required = false) String status, @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to, @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    CursorPage<EntryView> page = entries.list(new Filter(flatId, status, from, to, cursor, limit));
    return new CursorPage<>(page.items().stream().map(EntryResponse::from).toList(), page.nextCursor());
  }

  // ---- edge agent (docs/architecture/01 §3: /edge/sync) ---------------------------------------

  record EdgeBatch(@NotNull @Size(max = 500) List<@Valid EdgeItem> entries) {}

  record EdgeItem(@NotBlank @Size(max = 80) String clientEntryId, String passCode, String qrToken, UUID flatId,
      UUID gateId, @Size(max = 120) String visitorName, String purpose, @Size(max = 20) String vehicleReg,
      Instant inAt, Instant outAt) {}

  @PostMapping("/edge-sync")
  @PreAuthorize("@perm.has('gate:entry')")
  List<EdgeResult> edgeSync(@Valid @RequestBody EdgeBatch batch) {
    return entries.syncEdge(batch.entries().stream().map(i -> new EdgeEntry(i.clientEntryId(), i.passCode(),
        i.qrToken(), i.flatId(), i.gateId(), i.visitorName(), i.purpose(), i.vehicleReg(), i.inAt(), i.outAt()))
        .toList());
  }
}
