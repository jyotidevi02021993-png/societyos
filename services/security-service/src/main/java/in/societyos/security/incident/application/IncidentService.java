package in.societyos.security.incident.application;

import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.directory.application.GateAccess;
import in.societyos.security.incident.domain.GateIncident;
import in.societyos.security.incident.domain.IncidentEvents;
import in.societyos.security.incident.infrastructure.GateIncidentRepository;
import in.societyos.security.notification.application.Notifier;
import in.societyos.security.notification.domain.NotificationRequested;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.platform.events.DomainEvents;
import in.societyos.security.platform.security.PermissionEvaluator;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

  public record NewIncident(String kind, String severity, String locationText, String description, UUID photoMediaId,
      UUID gateId) {}

  private final GateIncidentRepository incidents;
  private final DirectoryService directory;
  private final GateAccess access;
  private final PermissionEvaluator perm;
  private final DomainEvents events;
  private final Notifier notifier;
  private final Clock clock;

  public IncidentService(GateIncidentRepository incidents, DirectoryService directory, GateAccess access,
      PermissionEvaluator perm, DomainEvents events, Notifier notifier, Clock clock) {
    this.incidents = incidents;
    this.directory = directory;
    this.access = access;
    this.perm = perm;
    this.events = events;
    this.notifier = notifier;
    this.clock = clock;
  }

  @Transactional
  public GateIncident report(NewIncident cmd) {
    GateIncident incident = incidents.save(new GateIncident(cmd.kind(), cmd.severity(), cmd.locationText(),
        cmd.description(), cmd.photoMediaId(), cmd.gateId(), access.userId(), clock.instant()));
    events.publish(IncidentEvents.reported(incident));
    if (incident.isSerious()) {
      List<UUID> to = directory.alertRecipients().stream().filter(u -> !u.equals(incident.getReportedBy())).toList();
      notifier.urgent(to, NotificationRequested.ALERT, "security.incident.reported",
          Map.of("incidentId", incident.getId().toString(), "kind", incident.getKind(), "severity",
              incident.getSeverity(), "location", incident.getLocationText() == null ? "" : incident.getLocationText()),
          "incident:" + incident.getId());
    }
    return incident;
  }

  /** Incident managers and the gate log see all; reporters see their own. */
  @Transactional(readOnly = true)
  public List<GateIncident> list() {
    UUID society = TenantContext.activeSocietyId();
    if (perm.hasAny("incident:manage", "gate:log-view")) {
      return incidents.findBySocietyIdOrderByAtDesc(society, Limit.of(200));
    }
    return incidents.findBySocietyIdAndReportedByOrderByAtDesc(society, access.userId(), Limit.of(100));
  }

  @Transactional(readOnly = true)
  public GateIncident get(UUID id) {
    GateIncident i = incidents.findById(id).orElseThrow(() -> ProblemException.notFound("incident", id));
    if (!perm.hasAny("incident:manage", "gate:log-view") && !i.getReportedBy().equals(access.userId())) {
      throw ProblemException.notFound("incident", id);
    }
    return i;
  }
}
