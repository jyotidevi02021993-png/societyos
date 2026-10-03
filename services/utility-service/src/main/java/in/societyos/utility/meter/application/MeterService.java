package in.societyos.utility.meter.application;

import in.societyos.utility.meter.domain.Meter;
import in.societyos.utility.meter.domain.ReadingRules;
import in.societyos.utility.meter.infrastructure.MeterRepository;
import in.societyos.utility.platform.core.error.ProblemException;
import in.societyos.utility.reference.application.ReferenceData;
import in.societyos.utility.reference.domain.AssetRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeterService {

  public record MeterInput(String code, String name, String system, UUID assetId, UUID locationId, String metric,
      String unit, String mode, BigDecimal expectedMin, BigDecimal expectedMax, BigDecimal maxDelta) {}

  private final MeterRepository meters;
  private final ReferenceData reference;

  public MeterService(MeterRepository meters, ReferenceData reference) {
    this.meters = meters;
    this.reference = reference;
  }

  @Transactional(readOnly = true)
  public List<Meter> list(String system) {
    return system == null || system.isBlank() ? meters.findAllByOrderBySystemAscNameAsc()
        : meters.findBySystemOrderByNameAsc(system.trim().toUpperCase(Locale.ROOT));
  }

  @Transactional(readOnly = true)
  public Meter get(UUID id) {
    return require(id);
  }

  @Transactional
  public Meter create(MeterInput in) {
    if (in.code() == null || in.code().isBlank()) {
      throw ProblemException.badRequest("CODE_REQUIRED", "code is required");
    }
    String code = in.code().trim().toUpperCase(Locale.ROOT);
    if (meters.existsByCodeIgnoreCase(code)) {
      throw ProblemException.conflict("METER_CODE_EXISTS", "Meter " + code + " already exists");
    }
    return meters.save(new Meter(code, details(in)));
  }

  @Transactional
  public Meter update(UUID id, MeterInput in) {
    Meter m = require(id);
    m.update(details(in));
    return meters.save(m);
  }

  @Transactional
  public Meter setActive(UUID id, boolean active) {
    Meter m = require(id);
    m.setActive(active);
    return meters.save(m);
  }

  Meter require(UUID id) {
    return meters.findById(id).orElseThrow(() -> ProblemException.notFound("meter", id));
  }

  private Meter.Details details(MeterInput in) {
    try {
      UUID location = in.locationId();
      if (location == null && in.assetId() != null) {
        location = reference.asset(in.assetId()).map(AssetRef::getLocationId).orElse(null);
      }
      ReadingRules.Mode mode = in.mode() == null ? null : ReadingRules.Mode.valueOf(in.mode().trim().toUpperCase(Locale.ROOT));
      String system = in.system() == null ? null : in.system().trim().toUpperCase(Locale.ROOT);
      return new Meter.Details(in.name(), system, in.assetId(), location, in.metric(), in.unit(), mode,
          in.expectedMin(), in.expectedMax(), in.maxDelta());
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_METER", e.getMessage());
    }
  }
}
