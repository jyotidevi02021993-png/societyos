package in.societyos.security.vehicle.api;

import in.societyos.security.vehicle.application.VehicleMovementService;
import in.societyos.security.vehicle.application.VehicleMovementService.MovementView;
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
@RequestMapping("/v1/vehicle-movements")
class VehicleMovementController {

  private final VehicleMovementService movements;

  VehicleMovementController(VehicleMovementService movements) {
    this.movements = movements;
  }

  record RecordMovement(@Size(max = 20) String regNo, @Size(max = 64) String rfidTag, String direction, UUID gateId) {}

  record MovementResponse(UUID id, boolean known, UUID vehicleId, UUID flatId, String flatLabel, String regNo,
      String vehicleKind, String direction, String matchedBy, UUID gateId, Instant at) {
    static MovementResponse from(MovementView v) {
      var m = v.movement();
      return new MovementResponse(m.getId(), m.isKnown(), m.getVehicleId(), m.getFlatId(), v.flatLabel(),
          m.getRegNo(), v.vehicleKind(), m.getDirection(), m.getMatchedBy(), m.getGateId(), m.getAt());
    }
  }

  /** Guard (or ANPR/RFID reader via the edge agent) records a vehicle; unknown vehicles are logged too. */
  @PostMapping
  @PreAuthorize("@perm.has('gate:entry')")
  MovementResponse record(@RequestBody RecordMovement r) {
    return MovementResponse.from(movements.record(r.regNo(), r.rfidTag(), r.direction(), r.gateId()));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('gate:entry', 'gate:log-view')")
  List<MovementResponse> list(@RequestParam(required = false) UUID flatId) {
    return movements.list(flatId).stream().map(MovementResponse::from).toList();
  }
}
