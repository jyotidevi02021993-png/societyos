package in.societyos.utility.meter.api;

import in.societyos.utility.meter.application.MeterService;
import in.societyos.utility.meter.application.ReadingService;
import in.societyos.utility.meter.domain.Meter;
import in.societyos.utility.meter.domain.Reading;
import in.societyos.utility.platform.web.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/meters} and {@code /v1/readings}. */
@RestController
public class MeterController {

  static final String VIEW = "@perm.hasAny('reading:record', 'dashboard:view', 'asset:view', 'signoff:daily')";

  private final MeterService meters;
  private final ReadingService readings;

  public MeterController(MeterService meters, ReadingService readings) {
    this.meters = meters;
    this.readings = readings;
  }

  public record MeterRequest(@Size(max = 40) String code, @NotBlank @Size(max = 120) String name,
      @NotBlank String system, UUID assetId, UUID locationId, @NotBlank String metric, @NotBlank String unit,
      @NotBlank String mode, BigDecimal expectedMin, BigDecimal expectedMax, BigDecimal maxDelta) {
    MeterService.MeterInput toInput() {
      return new MeterService.MeterInput(code, name, system, assetId, locationId, metric, unit, mode, expectedMin,
          expectedMax, maxDelta);
    }
  }

  public record MeterResponse(UUID id, String code, String name, String system, UUID assetId, UUID locationId,
      String metric, String unit, String mode, BigDecimal expectedMin, BigDecimal expectedMax, BigDecimal maxDelta,
      boolean active) {
    static MeterResponse from(Meter m) {
      return new MeterResponse(m.getId(), m.getCode(), m.getName(), m.getSystem(), m.getAssetId(), m.getLocationId(),
          m.getMetric(), m.getUnit(), m.getMode().name(), m.getExpectedMin(), m.getExpectedMax(), m.getMaxDelta(),
          m.isActive());
    }
  }

  public record ReadingRequest(@NotNull UUID meterId, @NotNull BigDecimal value, Instant at, String source,
      @Size(max = 500) String note, UUID photoMediaId) {
    ReadingService.ReadingInput toInput() {
      return new ReadingService.ReadingInput(meterId, value, at, source, note, photoMediaId);
    }
  }

  public record ReadingBatch(@NotEmpty @Size(max = 200) List<@Valid ReadingRequest> readings) {}

  public record ReadingResponse(UUID id, UUID meterId, UUID assetId, String metric, BigDecimal value, String unit,
      Instant at, BigDecimal delta, String source, UUID recordedBy, boolean anomaly, String anomalyReason,
      BigDecimal expectedMin, BigDecimal expectedMax, String note, UUID photoMediaId) {
    static ReadingResponse from(Reading r) {
      return new ReadingResponse(r.getId(), r.getMeterId(), r.getAssetId(), r.getMetric(), r.getValue(), r.getUnit(),
          r.getAt(), r.getDelta(), r.getSource(), r.getRecordedBy(), r.isAnomaly(), r.getAnomalyReason(),
          r.getExpectedMin(), r.getExpectedMax(), r.getNote(), r.getPhotoMediaId());
    }
  }

  // --- meters -----------------------------------------------------------------------------

  @GetMapping("/v1/meters")
  @PreAuthorize(VIEW)
  public List<MeterResponse> list(@RequestParam(required = false) String system) {
    return meters.list(system).stream().map(MeterResponse::from).toList();
  }

  @GetMapping("/v1/meters/{id}")
  @PreAuthorize(VIEW)
  public MeterResponse get(@PathVariable UUID id) {
    return MeterResponse.from(meters.get(id));
  }

  @PostMapping("/v1/meters")
  @PreAuthorize("@perm.hasAny('checklist:manage', 'asset:manage')")
  public ResponseEntity<MeterResponse> create(@Valid @RequestBody MeterRequest r) {
    MeterResponse saved = MeterResponse.from(meters.create(r.toInput()));
    return ResponseEntity.created(URI.create("/v1/meters/" + saved.id())).body(saved);
  }

  @PutMapping("/v1/meters/{id}")
  @PreAuthorize("@perm.hasAny('checklist:manage', 'asset:manage')")
  public MeterResponse update(@PathVariable UUID id, @Valid @RequestBody MeterRequest r) {
    return MeterResponse.from(meters.update(id, r.toInput()));
  }

  @PostMapping("/v1/meters/{id}/deactivate")
  @PreAuthorize("@perm.hasAny('checklist:manage', 'asset:manage')")
  public MeterResponse deactivate(@PathVariable UUID id) {
    return MeterResponse.from(meters.setActive(id, false));
  }

  @PostMapping("/v1/meters/{id}/activate")
  @PreAuthorize("@perm.hasAny('checklist:manage', 'asset:manage')")
  public MeterResponse activate(@PathVariable UUID id) {
    return MeterResponse.from(meters.setActive(id, true));
  }

  // --- readings ---------------------------------------------------------------------------

  @PostMapping("/v1/readings")
  @PreAuthorize("@perm.has('reading:record')")
  public ResponseEntity<ReadingResponse> record(@Valid @RequestBody ReadingRequest r) {
    ReadingResponse saved = ReadingResponse.from(readings.record(r.toInput()));
    return ResponseEntity.created(URI.create("/v1/readings/" + saved.id())).body(saved);
  }

  /** A whole round (all meters of the morning walk) in one request; all or nothing. */
  @PostMapping("/v1/readings/batch")
  @PreAuthorize("@perm.has('reading:record')")
  public List<ReadingResponse> recordBatch(@Valid @RequestBody ReadingBatch batch) {
    return readings.recordAll(batch.readings().stream().map(ReadingRequest::toInput).toList()).stream()
        .map(ReadingResponse::from).toList();
  }

  @GetMapping("/v1/readings")
  @PreAuthorize(VIEW)
  public List<ReadingResponse> readings(@RequestParam(required = false) UUID meterId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue = "false") boolean anomalyOnly, @RequestParam(required = false) Integer limit) {
    return readings.search(meterId, from, to, anomalyOnly, CursorPage.clampLimit(limit)).stream()
        .map(ReadingResponse::from).toList();
  }

  @GetMapping("/v1/readings/{id}")
  @PreAuthorize(VIEW)
  public ReadingResponse reading(@PathVariable UUID id) {
    return ReadingResponse.from(readings.get(id));
  }
}
