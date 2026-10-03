package in.societyos.security.sos.infrastructure;

import in.societyos.security.sos.domain.SosAlert;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SosRepository extends JpaRepository<SosAlert, UUID> {

  List<SosAlert> findBySocietyIdOrderByAtDesc(UUID societyId, Limit limit);

  List<SosAlert> findBySocietyIdAndResolvedAtIsNullOrderByAtDesc(UUID societyId);

  List<SosAlert> findBySocietyIdAndRaisedByOrderByAtDesc(UUID societyId, UUID raisedBy, Limit limit);
}
