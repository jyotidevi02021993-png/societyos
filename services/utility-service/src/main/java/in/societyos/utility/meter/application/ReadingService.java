package in.societyos.utility.meter.application;

import in.societyos.utility.common.Alerts;
import in.societyos.utility.meter.domain.Meter;
import in.societyos.utility.meter.domain.Reading;
import in.societyos.utility.meter.domain.ReadingEvents;
import in.societyos.utility.meter.domain.ReadingRules;
import in.societyos.utility.meter.infrastructure.ReadingRepository;
import in.societyos.utility.platform.core.error.ProblemException;
import in.societyos.utility.platform.core.tenant.TenantContext;
import in.societyos.utility.platform.events.DomainEvents;
import in.societyos.utility.reference.application.ReferenceData;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records readings, checks them against the meter's thresholds and publishes the events. */
@Service
public class ReadingService {

  /** Readings may be back-dated (paper log typed in later) but not beyond this. */
  static final Duration MAX_BACKDATE = Duration.ofDays(7);
  static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);

  public record ReadingInput(UUID meterId, BigDecimal value, Instant at, String source, String note, UUID photoMediaId) {}

  private final MeterService meters;
  private final ReadingRepository readings;
  private final DomainEvents events;
  private final Alerts alerts;
  private final ReferenceData reference;

  public ReadingService(MeterService meters, ReadingRepository readings, DomainEvents events, Alerts alerts,
      ReferenceData reference) {
    this.meters = meters;
    this.readings = readings;
    this.events = events;
    this.alerts = alerts;
    this.reference = reference;
  }

  @Transactional
  public Reading record(ReadingInput in) {
    if (in.meterId() == null || in.value() == null) {
      throw ProblemException.badRequest("INVALID_READING", "meterId and value are required");
    }
    Meter meter = meters.require(in.meterId());
    if (!meter.isActive()) {
      throw ProblemException.unprocessable("METER_INACTIVE", "Meter " + meter.getCode() + " is not active");
    }
    Instant now = reference.now();
    Instant at = in.at() == null ? now : in.at();
    if (at.isAfter(now.plus(MAX_CLOCK_SKEW)) || at.isBefore(now.minus(MAX_BACKDATE))) {
      throw ProblemException.badRequest("INVALID_READING_TIME", "A reading must be taken within the last 7 days");
    }
    String source = in.source() == null ? "MANUAL" : in.source().trim().toUpperCase(Locale.ROOT);
    if (!Reading.SOURCES.contains(source)) {
      throw ProblemException.badRequest("INVALID_SOURCE", "source must be one of " + Reading.SOURCES);
    }
    BigDecimal previous = readings.findFirstByMeterIdAndAtLessThanOrderByAtDesc(meter.getId(), at)
        .map(Reading::getValue).orElse(null);
    ReadingRules.Evaluation eval;
    try {
      eval = meter.evaluate(in.value(), previous);
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_READING", e.getMessage());
    }
    Reading r = readings.save(new Reading(meter, in.value(), at, eval, source, TenantContext.userId().orElse(null),
        clean(in.note()), in.photoMediaId()));
    events.publish(new ReadingEvents.ReadingRecorded(r.getId(), r.getAssetId(), meter.getId(), r.getMetric(),
        r.getValue(), r.getUnit(), r.getAt()));
    if (eval.anomaly()) {
      events.publish(new ReadingEvents.ReadingAnomaly(r.getId(), r.getAssetId(), r.getMetric(), r.getValue(),
          meter.getExpectedMin(), meter.getExpectedMax(), meter.getId(), eval.reason().name(), eval.delta()));
      alerts.notifyManagers("utility.reading.anomaly", Map.of("meterCode", meter.getCode(), "meterName", meter.getName(),
          "metric", r.getMetric(), "value", r.getValue().stripTrailingZeros().toPlainString(), "unit", r.getUnit(),
          "reason", eval.reason().name()), "HIGH", "reading-anomaly:" + r.getId());
    }
    return r;
  }

  /** A round of readings in one transaction: all or nothing. */
  @Transactional
  public List<Reading> recordAll(List<ReadingInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw ProblemException.badRequest("NO_READINGS", "Send at least one reading");
    }
    List<Reading> saved = new ArrayList<>();
    for (ReadingInput in : inputs) {
      saved.add(record(in));
    }
    return saved;
  }

  @Transactional(readOnly = true)
  public List<Reading> search(UUID meterId, Instant from, Instant to, boolean anomalyOnly, int limit) {
    Instant f = from == null ? reference.now().minus(Duration.ofDays(31)) : from;
    Instant t = to == null ? reference.now().plus(MAX_CLOCK_SKEW) : to;
    return readings.search(meterId, f, t, anomalyOnly, PageRequest.of(0, limit));
  }

  @Transactional(readOnly = true)
  public Reading get(UUID id) {
    return readings.findById(id).orElseThrow(() -> ProblemException.notFound("reading", id));
  }

  private static String clean(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
