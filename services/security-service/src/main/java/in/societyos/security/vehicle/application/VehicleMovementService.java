package in.societyos.security.vehicle.application;

import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.directory.application.DirectoryService.VehicleMatch;
import in.societyos.security.directory.application.GateAccess;
import in.societyos.security.directory.domain.FlatVehicle;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.vehicle.domain.VehicleMovement;
import in.societyos.security.vehicle.infrastructure.VehicleMovementRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resident vehicles at the gate: RFID or number-plate match against the society copy, then logged. */
@Service
public class VehicleMovementService {

  public record MovementView(VehicleMovement movement, String flatLabel, String vehicleKind) {}

  private final VehicleMovementRepository movements;
  private final DirectoryService directory;
  private final GateAccess access;
  private final Clock clock;

  public VehicleMovementService(VehicleMovementRepository movements, DirectoryService directory, GateAccess access,
      Clock clock) {
    this.movements = movements;
    this.directory = directory;
    this.access = access;
    this.clock = clock;
  }

  @Transactional
  public MovementView record(String regNo, String rfidTag, String direction, UUID gateId) {
    Optional<VehicleMatch> match = directory.matchVehicle(regNo, rfidTag);
    String dir = direction == null ? "IN" : direction.trim().toUpperCase();
    VehicleMovement m = match
        .map(v -> new VehicleMovement(v.vehicle().getId(), v.vehicle().getFlatId(), v.vehicle().getRegNo(), dir,
            v.matchedBy(), gateId, access.userId(), clock.instant()))
        .orElseGet(() -> new VehicleMovement(null, null, FlatVehicle.normaliseRegNo(regNo), dir, "NONE", gateId,
            access.userId(), clock.instant()));
    movements.save(m);
    return new MovementView(m, match.map(VehicleMatch::flatLabel).orElse(null),
        match.map(v -> v.vehicle().getKind()).orElse(null));
  }

  @Transactional(readOnly = true)
  public List<MovementView> list(UUID flatId) {
    UUID society = TenantContext.activeSocietyId();
    List<VehicleMovement> rows;
    if (flatId != null) {
      access.requireFlatView(flatId);
      rows = movements.findBySocietyIdAndFlatIdOrderByAtDesc(society, flatId, Limit.of(200));
    } else {
      rows = movements.findBySocietyIdOrderByAtDesc(society, Limit.of(200));
    }
    Map<UUID, String> labels = directory.labels(rows.stream().map(VehicleMovement::getFlatId).toList());
    return rows.stream().map(m -> new MovementView(m, labels.get(m.getFlatId()), null)).toList();
  }

  @Transactional
  public int purgeBefore(Instant before) {
    return movements.purge(TenantContext.activeSocietyId(), before);
  }
}
