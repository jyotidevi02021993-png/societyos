package in.societyos.society.household.api;

import in.societyos.society.household.application.VehicleService;
import in.societyos.society.household.application.VehicleService.VehicleView;
import in.societyos.society.platform.core.error.ProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Access per flat is checked in the use case (manager, or owner/tenant of that flat). */
@RestController
@RequestMapping("/v1/vehicles")
class VehicleController {

  private final VehicleService vehicles;

  VehicleController(VehicleService vehicles) {
    this.vehicles = vehicles;
  }

  record VehicleRequest(@NotNull UUID flatId, @NotBlank String regNo, @NotBlank String kind,
      @Size(max = 64) String rfidTag) {}

  record VehicleResponse(UUID id, UUID flatId, String flatLabel, String regNo, String kind, String rfidTag) {
    static VehicleResponse from(VehicleView v) {
      return new VehicleResponse(v.vehicle().getId(), v.vehicle().getFlatId(), v.flatLabel(), v.vehicle().getRegNo(),
          v.vehicle().getKind(), v.vehicle().getRfidTag());
    }
  }

  @GetMapping
  @PreAuthorize("isAuthenticated()")
  List<VehicleResponse> list(@RequestParam(required = false) UUID flatId) {
    return vehicles.list(flatId).stream().map(VehicleResponse::from).toList();
  }

  @GetMapping("/lookup")
  @PreAuthorize("@perm.hasAny('gate:entry', 'member:view', 'member:manage')")
  VehicleResponse lookup(@RequestParam(required = false) String regNo, @RequestParam(required = false) String rfidTag) {
    return vehicles.lookup(regNo, rfidTag).map(VehicleResponse::from)
        .orElseThrow(() -> ProblemException.notFound("vehicle", regNo != null ? regNo : rfidTag));
  }

  @PostMapping
  @PreAuthorize("@perm.hasAny('member:manage', 'household:manage')")
  ResponseEntity<VehicleResponse> register(@Valid @RequestBody VehicleRequest r) {
    VehicleView saved = vehicles.register(r.flatId(), r.regNo(), r.kind().trim().toUpperCase(), r.rfidTag());
    return ResponseEntity.created(URI.create("/v1/vehicles/" + saved.vehicle().getId()))
        .body(VehicleResponse.from(saved));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@perm.hasAny('member:manage', 'household:manage')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable UUID id) {
    vehicles.remove(id);
  }
}
