package in.societyos.security.sos.application;

import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.directory.application.GateAccess;
import in.societyos.security.notification.application.Notifier;
import in.societyos.security.notification.domain.NotificationRequested;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.platform.events.DomainEvents;
import in.societyos.security.sos.domain.SosAlert;
import in.societyos.security.sos.domain.SosEvents;
import in.societyos.security.sos.infrastructure.SosRepository;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SOS: raised by a resident (own flat) or guard; guards and managers are alerted at HIGH priority. */
@Service
public class SosService {

  public record SosView(SosAlert sos, String flatLabel) {}

  private final SosRepository alerts;
  private final DirectoryService directory;
  private final GateAccess access;
  private final DomainEvents events;
  private final Notifier notifier;
  private final Clock clock;

  public SosService(SosRepository alerts, DirectoryService directory, GateAccess access, DomainEvents events,
      Notifier notifier, Clock clock) {
    this.alerts = alerts;
    this.directory = directory;
    this.access = access;
    this.events = events;
    this.notifier = notifier;
    this.clock = clock;
  }

  @Transactional
  public SosView raise(String kind, UUID flatId, String note) {
    UUID flat = flatId;
    if (flat != null) {
      if (!access.isGuard() && !access.isResidentOf(flat)) {
        throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can raise an SOS for your own flat only");
      }
    } else {
      List<UUID> mine = access.myFlatIds();
      flat = mine.size() == 1 ? mine.getFirst() : null;
    }
    SosAlert sos = alerts.save(new SosAlert(access.userId(), flat, kind, note, clock.instant()));
    events.publish(SosEvents.raised(sos));
    String label = label(sos);
    Map<String, String> params = new HashMap<>();
    params.put("sosId", sos.getId().toString());
    params.put("kind", sos.getKind());
    params.put("flatLabel", label == null ? "" : label);
    List<UUID> responders = directory.alertRecipients().stream().filter(u -> !u.equals(sos.getRaisedBy())).toList();
    notifier.urgent(responders, NotificationRequested.ALERT, "security.sos.raised", params, "sos:" + sos.getId());
    return new SosView(sos, label);
  }

  @Transactional
  public SosView acknowledge(UUID id) {
    requireResponder();
    SosAlert s = require(id);
    s.acknowledge(access.userId(), clock.instant());
    return new SosView(alerts.save(s), label(s));
  }

  @Transactional
  public SosView resolve(UUID id) {
    requireResponder();
    SosAlert s = require(id);
    s.resolve(access.userId(), clock.instant());
    SosAlert saved = alerts.save(s);
    notifier.info(List.of(s.getRaisedBy()), NotificationRequested.ALERT, "security.sos.resolved",
        Map.of("sosId", s.getId().toString(), "kind", s.getKind()), "sos:" + s.getId() + ":resolved");
    return new SosView(saved, label(s));
  }

  /** Responders see every alert (or only open ones); everyone else sees what they raised. */
  @Transactional(readOnly = true)
  public List<SosView> list(boolean openOnly) {
    UUID society = TenantContext.activeSocietyId();
    List<SosAlert> rows;
    if (access.canRespondToAlerts() || access.canSeeSocietyLog()) {
      rows = openOnly ? alerts.findBySocietyIdAndResolvedAtIsNullOrderByAtDesc(society)
          : alerts.findBySocietyIdOrderByAtDesc(society, Limit.of(200));
    } else {
      rows = alerts.findBySocietyIdAndRaisedByOrderByAtDesc(society, access.userId(), Limit.of(50)).stream()
          .filter(s -> !openOnly || s.getResolvedAt() == null).toList();
    }
    Map<UUID, String> labels = directory.labels(rows.stream().map(SosAlert::getFlatId).toList());
    return rows.stream().map(s -> new SosView(s, labels.get(s.getFlatId()))).toList();
  }

  private void requireResponder() {
    if (!access.canRespondToAlerts()) {
      throw ProblemException.forbidden("NOT_A_RESPONDER", "Only guards and incident managers respond to SOS");
    }
  }

  private SosAlert require(UUID id) {
    return alerts.findById(id).orElseThrow(() -> ProblemException.notFound("sos", id));
  }

  private String label(SosAlert s) {
    return s.getFlatId() == null ? null : directory.labels(List.of(s.getFlatId())).get(s.getFlatId());
  }
}
