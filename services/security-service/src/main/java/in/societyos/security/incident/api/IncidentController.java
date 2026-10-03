package in.societyos.security.incident.api;

import in.societyos.security.incident.application.IncidentService;
import in.societyos.security.incident.application.IncidentService.NewIncident;
import in.societyos.security.incident.domain.GateIncident;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/incidents")
class IncidentController {

  private final IncidentService incidents;

  IncidentController(IncidentService incidents) {
    this.incidents = incidents;
  }

  record ReportIncident(@NotBlank @Size(max = 40) String kind, String severity, @Size(max = 200) String locationText,
      @Size(max = 2000) String description, UUID photoMediaId, UUID gateId) {}

  record IncidentResponse(UUID id, String kind, String severity, String locationText, String description,
      UUID photoMediaId, UUID gateId, UUID reportedBy, Instant at) {
    static IncidentResponse from(GateIncident i) {
      return new IncidentResponse(i.getId(), i.getKind(), i.getSeverity(), i.getLocationText(), i.getDescription(),
          i.getPhotoMediaId(), i.getGateId(), i.getReportedBy(), i.getAt());
    }
  }

  @PostMapping
  @PreAuthorize("@perm.has('incident:report')")
  ResponseEntity<IncidentResponse> report(@Valid @RequestBody ReportIncident r) {
    GateIncident i = incidents.report(new NewIncident(r.kind(), r.severity(), r.locationText(), r.description(),
        r.photoMediaId(), r.gateId()));
    return ResponseEntity.created(URI.create("/v1/incidents/" + i.getId())).body(IncidentResponse.from(i));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('incident:report', 'incident:manage', 'gate:log-view')")
  List<IncidentResponse> list() {
    return incidents.list().stream().map(IncidentResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('incident:report', 'incident:manage', 'gate:log-view')")
  IncidentResponse get(@PathVariable UUID id) {
    return IncidentResponse.from(incidents.get(id));
  }
}
