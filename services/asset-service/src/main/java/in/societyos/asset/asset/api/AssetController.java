package in.societyos.asset.asset.api;

import in.societyos.asset.asset.application.AssetHistoryService;
import in.societyos.asset.asset.application.AssetProfileQuery;
import in.societyos.asset.asset.application.AssetService;
import in.societyos.asset.asset.domain.Asset;
import in.societyos.asset.asset.domain.AssetHistory;
import in.societyos.asset.platform.web.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/assets")
public class AssetController {

  private final AssetService assets;
  private final AssetProfileQuery profiles;
  private final AssetHistoryService history;

  public AssetController(AssetService assets, AssetProfileQuery profiles, AssetHistoryService history) {
    this.assets = assets;
    this.profiles = profiles;
    this.history = history;
  }

  public record AssetRequest(@Size(max = 40) String code, @NotBlank @Size(max = 160) String name, String category,
      UUID categoryId, UUID locationId, String make, String model, String serialNo, String manufacturer,
      String supplier, String engineNo, String alternatorNo, String capacity, String maintenanceAgency,
      UUID vendorId, List<UUID> photoMediaIds, UUID photoMediaId, LocalDate purchaseDate, LocalDate installationDate,
      Long costPaise, Integer expectedLifeMonths, LocalDate warrantyUntil, LocalDate amcUntil, String pmFrequency,
      Map<String, Object> spec) {

    AssetService.AssetInput toInput() {
      List<UUID> photos = photoMediaIds != null ? photoMediaIds : photoMediaId != null ? List.of(photoMediaId) : null;
      return new AssetService.AssetInput(code, name, category, categoryId, locationId, make, model, serialNo,
          manufacturer, supplier, engineNo, alternatorNo, capacity, maintenanceAgency, vendorId, photos,
          purchaseDate, installationDate, costPaise, expectedLifeMonths, warrantyUntil, amcUntil, pmFrequency, spec);
    }
  }

  public record AssetResponse(UUID id, String assetCode, String name, String category, UUID categoryId,
      UUID locationId, String locationName, String make, String model, String serialNo, String manufacturer,
      String supplier, String engineNo, String alternatorNo, String capacity, String maintenanceAgency,
      UUID vendorId, String vendorName, UUID photoMediaId, List<UUID> photoMediaIds, LocalDate purchaseDate,
      LocalDate installationDate, Long costPaise, Integer expectedLifeMonths, LocalDate warrantyUntil,
      LocalDate amcUntil, String pmFrequency, Map<String, Object> spec, String status, String qrToken,
      Instant qrPrintedAt, int breakdownCount, long totalMaintenanceCostPaise, LocalDate disposedOn,
      Instant updatedAt) {

    public static AssetResponse from(AssetService.AssetView v) {
      Asset a = v.asset();
      return new AssetResponse(a.getId(), a.getAssetCode(), a.getName(), a.getCategory(), a.getCategoryId(),
          a.getLocationId(), v.locationName(), a.getMake(), a.getModel(), a.getSerialNo(), a.getManufacturer(),
          a.getSupplier(), a.getEngineNo(), a.getAlternatorNo(), a.getCapacity(), a.getMaintenanceAgency(),
          a.getVendorId(), v.vendorName(), a.getPhotoMediaId(), a.getPhotoMediaIds(), a.getPurchaseDate(),
          a.getInstallationDate(), a.getCostPaise(), a.getExpectedLifeMonths(), a.getWarrantyUntil(), a.getAmcUntil(),
          a.getPmFrequency(), v.spec(), a.getStatus(), a.getQrToken(), a.getQrPrintedAt(), a.getBreakdownCount(),
          a.getTotalMaintenanceCostPaise(), a.getDisposedOn(), a.getUpdatedAt());
    }
  }

  public record HistoryResponse(UUID id, Instant at, String kind, String refType, UUID refId, String summary,
      long costPaise, UUID actorId) {
    public static HistoryResponse from(AssetHistory h) {
      return new HistoryResponse(h.getId(), h.getAt(), h.getKind().name(), h.getRefType(), h.getRefId(), h.getSummary(),
          h.getCostPaise(), h.getActorId());
    }
  }

  public record StatusRequest(@NotBlank String status, @Size(max = 500) String reason) {}

  public record BreakdownRequest(@NotBlank @Size(max = 1000) String fault, String priority) {}

  public record DisposeRequest(@Size(max = 500) String reason) {}

  public record PhotoRequest(@NotNull UUID mediaId) {}

  @GetMapping
  @PreAuthorize("@perm.has('asset:view')")
  public List<AssetResponse> list(@RequestParam(required = false) String category,
      @RequestParam(required = false) String status, @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
    return assets.search(category, status, locationId, q, CursorPage.clampLimit(limit)).stream()
        .map(AssetResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.has('asset:view')")
  public AssetResponse get(@PathVariable UUID id) {
    return AssetResponse.from(assets.get(id));
  }

  /** Full profile for the QR scan screen: asset, plans, open PM tasks, coverage, recent history. */
  @GetMapping("/qr/{token}")
  @PreAuthorize("@perm.has('asset:view')")
  public ProfileResponse byQr(@PathVariable String token) {
    return ProfileResponse.from(profiles.byQr(token));
  }

  @GetMapping("/{id}/profile")
  @PreAuthorize("@perm.has('asset:view')")
  public ProfileResponse profile(@PathVariable UUID id) {
    return ProfileResponse.from(profiles.byId(id));
  }

  @GetMapping("/{id}/history")
  @PreAuthorize("@perm.has('asset:view')")
  public List<HistoryResponse> history(@PathVariable UUID id, @RequestParam(required = false) Integer limit) {
    assets.get(id);
    return history.timeline(id, CursorPage.clampLimit(limit)).stream().map(HistoryResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('asset:manage')")
  public ResponseEntity<AssetResponse> create(@Valid @RequestBody AssetRequest request) {
    AssetResponse saved = AssetResponse.from(assets.create(request.toInput()));
    return ResponseEntity.created(URI.create("/v1/assets/" + saved.id())).body(saved);
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('asset:manage')")
  public AssetResponse update(@PathVariable UUID id, @Valid @RequestBody AssetRequest request) {
    return AssetResponse.from(assets.update(id, request.toInput()));
  }

  @PostMapping("/{id}/status")
  @PreAuthorize("@perm.has('asset:manage')")
  public AssetResponse status(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
    return AssetResponse.from(assets.changeStatus(id, request.status(), request.reason()));
  }

  /** Staff report a breakdown; ticket-service opens the ticket from {@code asset.breakdown.reported}. */
  @PostMapping("/{id}/breakdown")
  @PreAuthorize("@perm.hasAny('breakdown:report', 'asset:manage')")
  public AssetResponse breakdown(@PathVariable UUID id, @Valid @RequestBody BreakdownRequest request) {
    return AssetResponse.from(assets.reportBreakdown(id, request.fault(), request.priority(), "MANUAL", null));
  }

  @PostMapping("/{id}/dispose")
  @PreAuthorize("@perm.has('asset:manage')")
  public AssetResponse dispose(@PathVariable UUID id, @Valid @RequestBody(required = false) DisposeRequest request) {
    return AssetResponse.from(assets.dispose(id, request == null ? null : request.reason()));
  }

  @PostMapping("/{id}/photos")
  @PreAuthorize("@perm.has('asset:manage')")
  public AssetResponse addPhoto(@PathVariable UUID id, @Valid @RequestBody PhotoRequest request) {
    return AssetResponse.from(assets.addPhoto(id, request.mediaId()));
  }

  @PostMapping("/{id}/qr/regenerate")
  @PreAuthorize("@perm.has('asset:manage')")
  public AssetResponse regenerateQr(@PathVariable UUID id) {
    return AssetResponse.from(assets.regenerateQr(id));
  }

  @GetMapping("/{id}/qr-label")
  @PreAuthorize("@perm.has('asset:view')")
  public QrLabelController.LabelResponse label(@PathVariable UUID id) {
    return assets.labels(List.of(id)).stream().map(QrLabelController.LabelResponse::from).findFirst()
        .orElseThrow(() -> in.societyos.asset.platform.core.error.ProblemException.unprocessable(
            "ASSET_HAS_NO_QR", "Asset " + id + " has no QR code"));
  }
}
