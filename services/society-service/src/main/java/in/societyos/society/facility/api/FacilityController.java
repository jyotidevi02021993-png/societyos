package in.societyos.society.facility.api;

import in.societyos.society.facility.application.FacilityService;
import in.societyos.society.facility.application.FacilityService.FacilityView;
import in.societyos.society.facility.domain.BookingRules;
import in.societyos.society.facility.domain.Facility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/facilities")
class FacilityController {

  private final FacilityService facilities;

  FacilityController(FacilityService facilities) {
    this.facilities = facilities;
  }

  record FacilityRequest(@NotBlank String kind, @NotBlank @Size(max = 120) String name,
      @Min(1) @Max(10_000) int capacity, boolean chargeable, @Min(0) long chargePaise,
      BookingRules bookingRules, String status) {
    Facility.Details details() {
      return new Facility.Details(kind.trim(), name, capacity, chargeable, chargePaise,
          status == null || status.isBlank() ? null : status.trim());
    }
  }

  record FacilityResponse(UUID id, String kind, String name, int capacity, boolean chargeable, long chargePaise,
      BookingRules bookingRules, String status) {
    static FacilityResponse from(FacilityView v) {
      Facility f = v.facility();
      return new FacilityResponse(f.getId(), f.getKind(), f.getName(), f.getCapacity(), f.isChargeable(),
          f.getChargePaise(), v.rules(), f.getStatus());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.has('society:view')")
  List<FacilityResponse> list() {
    return facilities.list().stream().map(FacilityResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('society:manage')")
  ResponseEntity<FacilityResponse> create(@Valid @RequestBody FacilityRequest r) {
    FacilityView saved = facilities.create(r.details(), r.bookingRules());
    return ResponseEntity.created(URI.create("/v1/facilities/" + saved.facility().getId()))
        .body(FacilityResponse.from(saved));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('society:manage')")
  FacilityResponse update(@PathVariable UUID id, @Valid @RequestBody FacilityRequest r) {
    return FacilityResponse.from(facilities.update(id, r.details(), r.bookingRules()));
  }
}
