package in.societyos.society.society.api;

import in.societyos.society.society.application.TowerService;
import in.societyos.society.society.domain.Tower;
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
@RequestMapping("/v1/towers")
class TowerController {

  private final TowerService towers;

  TowerController(TowerService towers) {
    this.towers = towers;
  }

  record TowerRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 10) String code,
      @Min(0) @Max(200) int floorsCount) {}

  record TowerResponse(UUID id, String name, String code, int floorsCount) {
    static TowerResponse from(Tower tower) {
      return new TowerResponse(tower.getId(), tower.getName(), tower.getCode(), tower.getFloorsCount());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.has('society:view')")
  List<TowerResponse> list() {
    return towers.list().stream().map(TowerResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('society:manage')")
  ResponseEntity<TowerResponse> create(@Valid @RequestBody TowerRequest r) {
    Tower saved = towers.create(r.name(), r.code(), r.floorsCount());
    return ResponseEntity.created(URI.create("/v1/towers/" + saved.getId())).body(TowerResponse.from(saved));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('society:manage')")
  TowerResponse update(@PathVariable UUID id, @Valid @RequestBody TowerRequest r) {
    return TowerResponse.from(towers.update(id, r.name(), r.code(), r.floorsCount()));
  }
}
