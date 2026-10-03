package in.societyos.security.gatepass.infrastructure;

import in.societyos.security.gatepass.domain.GatePass;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatePassRepository extends JpaRepository<GatePass, UUID> {

  Optional<GatePass> findBySocietyIdAndCodeAndStatus(UUID societyId, String code, String status);

  Optional<GatePass> findFirstBySocietyIdAndCodeOrderByCreatedAtDesc(UUID societyId, String code);

  Optional<GatePass> findBySocietyIdAndQrToken(UUID societyId, String qrToken);

  boolean existsBySocietyIdAndCodeAndStatus(UUID societyId, String code, String status);

  List<GatePass> findBySocietyIdAndFlatIdInAndValidToAfterOrderByValidFromDesc(
      UUID societyId, Collection<UUID> flatIds, Instant after);

  List<GatePass> findBySocietyIdAndValidToAfterOrderByValidFromDesc(UUID societyId, Instant after, Limit limit);

  /** Edge agent delta sync: passes changed since the cursor. */
  List<GatePass> findBySocietyIdAndUpdatedAtAfterOrderByUpdatedAtAsc(UUID societyId, Instant since, Limit limit);

  List<GatePass> findBySocietyIdAndStatusAndValidToLessThanEqual(UUID societyId, String status, Instant now,
      Limit limit);
}
