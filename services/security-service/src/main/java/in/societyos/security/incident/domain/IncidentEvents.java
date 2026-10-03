package in.societyos.security.incident.domain;

import in.societyos.security.common.SecurityEvent;
import java.util.UUID;

public final class IncidentEvents {

  private IncidentEvents() {}

  /** {@code security.incident.reported}: no description text (it may name people). */
  public record IncidentReported(UUID incidentId, String kind, String severity, String locationText, UUID reportedBy)
      implements SecurityEvent {
    @Override public String type() { return "security.incident.reported"; }
    @Override public UUID aggregateId() { return incidentId; }
  }

  public static IncidentReported reported(GateIncident i) {
    return new IncidentReported(i.getId(), i.getKind(), i.getSeverity(), i.getLocationText(), i.getReportedBy());
  }
}
