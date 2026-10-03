package in.societyos.society.location.api;

import in.societyos.society.location.application.LocationService;
import in.societyos.society.location.domain.Location;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/locations")
class LocationController {

  private final LocationService locations;

  LocationController(LocationService locations) {
    this.locations = locations;
  }

  record LocationRequest(@NotBlank String kind, @NotBlank @Size(max = 120) String name, UUID towerId, UUID parentId) {}

  record LocationResponse(UUID id, String kind, String name, UUID towerId, UUID parentId) {
    static LocationResponse from(Location l) {
      return new LocationResponse(l.getId(), l.getKind(), l.getName(), l.getTowerId(), l.getParentId());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.has('society:view')")
  List<LocationResponse> list() {
    return locations.list().stream().map(LocationResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('society:manage')")
  ResponseEntity<LocationResponse> create(@Valid @RequestBody LocationRequest r) {
    Location saved = locations.create(r.kind().trim(), r.name(), r.towerId(), r.parentId());
    return ResponseEntity.created(URI.create("/v1/locations/" + saved.getId())).body(LocationResponse.from(saved));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('society:manage')")
  LocationResponse update(@PathVariable UUID id, @Valid @RequestBody LocationRequest r) {
    return LocationResponse.from(locations.update(id, r.kind().trim(), r.name(), r.towerId(), r.parentId()));
  }
}
