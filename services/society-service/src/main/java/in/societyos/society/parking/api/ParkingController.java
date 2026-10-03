package in.societyos.society.parking.api;

import in.societyos.society.parking.application.ParkingService;
import in.societyos.society.parking.application.ParkingService.SlotView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/parking-slots")
class ParkingController {

  private final ParkingService parking;

  ParkingController(ParkingService parking) {
    this.parking = parking;
  }

  record SlotRequest(@NotBlank @Size(max = 20) String code, @NotBlank String kind) {}

  record AssignmentRequest(UUID flatId) {}

  record SlotResponse(UUID id, String code, String kind, UUID flatId, String flatLabel) {
    static SlotResponse from(SlotView v) {
      return new SlotResponse(v.slot().getId(), v.slot().getCode(), v.slot().getKind(), v.slot().getFlatId(),
          v.flatLabel());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.has('society:view')")
  List<SlotResponse> list(@RequestParam(required = false) UUID flatId) {
    return parking.list(flatId).stream().map(SlotResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('society:manage')")
  ResponseEntity<SlotResponse> create(@Valid @RequestBody SlotRequest r) {
    SlotView saved = parking.create(r.code(), r.kind().trim());
    return ResponseEntity.created(URI.create("/v1/parking-slots/" + saved.slot().getId()))
        .body(SlotResponse.from(saved));
  }

  @PutMapping("/{id}/assignment")
  @PreAuthorize("@perm.has('society:manage')")
  SlotResponse assign(@PathVariable UUID id, @RequestBody AssignmentRequest r) {
    return SlotResponse.from(parking.assign(id, r.flatId()));
  }
}
