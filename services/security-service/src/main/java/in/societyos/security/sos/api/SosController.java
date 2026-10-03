package in.societyos.security.sos.api;

import in.societyos.security.sos.application.SosService;
import in.societyos.security.sos.application.SosService.SosView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@RestController
@RequestMapping("/v1/sos")
class SosController {

  private final SosService sos;

  SosController(SosService sos) {
    this.sos = sos;
  }

  record RaiseSos(@NotBlank String kind, UUID flatId, @Size(max = 500) String note) {}

  record SosResponse(UUID id, String kind, String state, UUID flatId, String flatLabel, UUID raisedBy, String note,
      Instant at, UUID acknowledgedBy, Instant acknowledgedAt, UUID resolvedBy, Instant resolvedAt) {
    static SosResponse from(SosView v) {
      var s = v.sos();
      return new SosResponse(s.getId(), s.getKind(), s.state(), s.getFlatId(), v.flatLabel(), s.getRaisedBy(),
          s.getNote(), s.getAt(), s.getAcknowledgedBy(), s.getAcknowledgedAt(), s.getResolvedBy(), s.getResolvedAt());
    }
  }

  @PostMapping
  @PreAuthorize("@perm.has('sos:raise')")
  ResponseEntity<SosResponse> raise(@Valid @RequestBody RaiseSos r) {
    SosView v = sos.raise(r.kind(), r.flatId(), r.note());
    return ResponseEntity.created(URI.create("/v1/sos/" + v.sos().getId())).body(SosResponse.from(v));
  }

  @PostMapping("/{id}/acknowledge")
  @PreAuthorize("@perm.hasAny('gate:entry', 'incident:manage')")
  SosResponse acknowledge(@PathVariable UUID id) {
    return SosResponse.from(sos.acknowledge(id));
  }

  @PostMapping("/{id}/resolve")
  @PreAuthorize("@perm.hasAny('gate:entry', 'incident:manage')")
  SosResponse resolve(@PathVariable UUID id) {
    return SosResponse.from(sos.resolve(id));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('sos:raise', 'gate:entry', 'gate:log-view', 'incident:manage')")
  List<SosResponse> list(@RequestParam(defaultValue = "false") boolean open) {
    return sos.list(open).stream().map(SosResponse::from).toList();
  }
}
