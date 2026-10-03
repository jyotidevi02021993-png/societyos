package in.societyos.society.society.api;

import in.societyos.society.society.application.FlatService;
import in.societyos.society.society.domain.Flat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/v1/flats")
class FlatController {

  private final FlatService flats;

  FlatController(FlatService flats) {
    this.flats = flats;
  }

  record FlatRequest(@NotNull UUID towerId, @NotBlank @Size(max = 20) String number, @Min(0) @Max(200) int floor,
      @Min(1) Integer areaSqft, @Size(max = 30) String flatType) {}

  record FlatUpdateRequest(@Min(0) @Max(200) int floor, @Min(1) Integer areaSqft, @Size(max = 30) String flatType,
      @NotBlank String status) {}

  record FlatResponse(UUID id, UUID towerId, String number, String label, int floor,
      Integer areaSqft, String flatType, String status) {
    static FlatResponse from(Flat flat) {
      return new FlatResponse(flat.getId(), flat.getTowerId(), flat.getNumber(), flat.getLabel(),
          flat.getFloor(), flat.getAreaSqft(), flat.getFlatType(), flat.getStatus());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.has('society:view')")
  List<FlatResponse> list(@RequestParam(required = false) UUID towerId) {
    return flats.list(towerId).stream().map(FlatResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.has('society:view')")
  FlatResponse get(@PathVariable UUID id) {
    return FlatResponse.from(flats.require(id));
  }

  @PostMapping
  @PreAuthorize("@perm.has('society:manage')")
  ResponseEntity<FlatResponse> create(@Valid @RequestBody FlatRequest r) {
    Flat saved = flats.create(r.towerId(), r.number(), r.floor(), r.areaSqft(), r.flatType());
    return ResponseEntity.created(URI.create("/v1/flats/" + saved.getId())).body(FlatResponse.from(saved));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('society:manage')")
  FlatResponse update(@PathVariable UUID id, @Valid @RequestBody FlatUpdateRequest r) {
    return FlatResponse.from(flats.update(id, r.floor(), r.areaSqft(), r.flatType(), r.status().trim()));
  }
}
