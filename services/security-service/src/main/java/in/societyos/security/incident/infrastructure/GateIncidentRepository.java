package in.societyos.security.incident.infrastructure;

import in.societyos.security.incident.domain.GateIncident;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateIncidentRepository extends JpaRepository<GateIncident, UUID> {

  List<GateIncident> findBySocietyIdOrderByAtDesc(UUID societyId, Limit limit);

  List<GateIncident> findBySocietyIdAndReportedByOrderByAtDesc(UUID societyId, UUID reportedBy, Limit limit);
}
