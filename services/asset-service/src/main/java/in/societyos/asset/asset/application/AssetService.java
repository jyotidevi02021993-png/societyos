package in.societyos.asset.asset.application;

import in.societyos.asset.asset.domain.Asset;
import in.societyos.asset.asset.domain.AssetEvents;
import in.societyos.asset.asset.domain.AssetHistory;
import in.societyos.asset.asset.infrastructure.AssetCategoryRepository;
import in.societyos.asset.asset.infrastructure.AssetRepository;
import in.societyos.asset.coverage.domain.AmcContract;
import in.societyos.asset.coverage.domain.Warranty;
import in.societyos.asset.coverage.infrastructure.AmcContractRepository;
import in.societyos.asset.coverage.infrastructure.WarrantyRepository;
import in.societyos.asset.platform.core.error.ProblemException;
import in.societyos.asset.platform.core.tenant.TenantContext;
import in.societyos.asset.platform.events.DomainEvents;
import in.societyos.asset.platform.jpa.DocumentNumberService;
import in.societyos.asset.pm.application.PmPlanService;
import in.societyos.asset.pm.domain.PmFrequency;
import in.societyos.asset.reference.application.ReferenceData;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Asset registry use cases: master data, status, breakdown handoff, QR labels. */
@Service
public class AssetService {

  static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3", "P4");

  /** What a caller supplies to register or edit an asset. */
  public record AssetInput(String code, String name, String category, UUID categoryId, UUID locationId, String make,
      String model, String serialNo, String manufacturer, String supplier, String engineNo, String alternatorNo,
      String capacity, String maintenanceAgency, UUID vendorId, List<UUID> photoMediaIds, LocalDate purchaseDate,
      LocalDate installationDate, Long costPaise, Integer expectedLifeMonths, LocalDate warrantyUntil,
      LocalDate amcUntil, String pmFrequency, Map<String, Object> spec) {}

  /** An asset with the names other services own. */
  public record AssetView(Asset asset, String locationName, String vendorName, Map<String, Object> spec) {}

  /** Printable QR label data. */
  public record QrLabel(UUID assetId, String assetCode, String name, String category, String locationName,
      String qrToken, String qrPayload) {}

  private final AssetRepository assets;
  private final AssetCategoryRepository categories;
  private final WarrantyRepository warranties;
  private final AmcContractRepository amcs;
  private final AssetHistoryService history;
  private final PmPlanService pmPlans;
  private final ReferenceData reference;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final JsonMapper json;
  private final Clock clock;
  private final String qrBaseUrl;

  public AssetService(AssetRepository assets, AssetCategoryRepository categories, WarrantyRepository warranties,
      AmcContractRepository amcs, AssetHistoryService history, PmPlanService pmPlans, ReferenceData reference,
      DocumentNumberService numbers, DomainEvents events, JsonMapper json, Clock clock,
      @Value("${sos.asset.qr-base-url:https://app.societyos.in/a/}") String qrBaseUrl) {
    this.assets = assets;
    this.categories = categories;
    this.warranties = warranties;
    this.amcs = amcs;
    this.history = history;
    this.pmPlans = pmPlans;
    this.reference = reference;
    this.numbers = numbers;
    this.events = events;
    this.json = json;
    this.clock = clock;
    this.qrBaseUrl = qrBaseUrl.endsWith("/") ? qrBaseUrl : qrBaseUrl + "/";
  }

  // --- queries ----------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<AssetView> search(String category, String status, UUID locationId, String q, int limit) {
    String cat = category == null || category.isBlank() ? null : category.trim().toUpperCase(Locale.ROOT);
    String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
    String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
    return views(assets.search(cat, st, locationId, like, PageRequest.of(0, limit)));
  }

  @Transactional(readOnly = true)
  public AssetView get(UUID id) {
    return views(List.of(require(id))).getFirst();
  }

  @Transactional(readOnly = true)
  public AssetView byQr(String token) {
    Asset asset = assets.findByQrToken(token).orElseThrow(() -> ProblemException.notFound("asset", "qr:" + token));
    return views(List.of(asset)).getFirst();
  }

  @Transactional(readOnly = true)
  public List<AssetView> changedSince(Instant since, int limit) {
    return views(assets.findByUpdatedAtAfterOrderByUpdatedAtAsc(since, PageRequest.of(0, limit)));
  }

  // --- master data ------------------------------------------------------------------------

  @Transactional
  public AssetView create(AssetInput in) {
    reference.ensureSocietyKnown();
    Asset.Details d = details(in);
    String code = in.code() == null || in.code().isBlank() ? numbers.next("AST") : in.code().trim().toUpperCase(Locale.ROOT);
    if (assets.existsByAssetCodeIgnoreCase(code)) {
      throw ProblemException.conflict("ASSET_CODE_EXISTS", "Asset code " + code + " is already used");
    }
    Asset asset = assets.save(new Asset(code, d));
    history.record(asset.getId(), AssetHistory.Kind.CREATED, "Registered " + asset.getName() + " (" + code + ")");
    syncCoverage(asset, null, null);
    if (asset.getPmFrequency() != null) {
      PmFrequency f = PmFrequency.valueOf(asset.getPmFrequency());
      if (f.isCalendar()) {
        LocalDate anchor = asset.getInstallationDate() != null && !asset.getInstallationDate().isBefore(reference.today())
            ? asset.getInstallationDate() : reference.today();
        pmPlans.createDefault(asset, f, anchor);
      }
    }
    events.publish(new AssetEvents.AssetCreated(asset.getId(), asset.getAssetCode(), asset.getName(),
        asset.getCategory(), asset.getLocationId(), asset.getStatus()));
    return get(asset.getId());
  }

  @Transactional
  public AssetView update(UUID id, AssetInput in) {
    Asset asset = require(id);
    if (asset.status() == Asset.Status.DISPOSED) {
      throw ProblemException.unprocessable("ASSET_DISPOSED", "A disposed asset cannot be edited");
    }
    LocalDate oldWarranty = asset.getWarrantyUntil();
    LocalDate oldAmc = asset.getAmcUntil();
    asset.update(details(in));
    syncCoverage(asset, oldWarranty, oldAmc);
    history.record(asset.getId(), AssetHistory.Kind.UPDATED, "Details updated");
    events.publish(new AssetEvents.AssetUpdated(asset.getId(), asset.getAssetCode(), asset.getName(),
        asset.getCategory(), asset.getLocationId(), asset.getStatus()));
    return get(assets.save(asset).getId());
  }

  @Transactional
  public AssetView addPhoto(UUID id, UUID mediaId) {
    Asset asset = require(id);
    asset.addPhoto(mediaId);
    return get(assets.save(asset).getId());
  }

  // --- status and breakdown ---------------------------------------------------------------

  @Transactional
  public AssetView changeStatus(UUID id, String status, String reason) {
    Asset.Status to;
    try {
      to = Asset.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw ProblemException.badRequest("INVALID_STATUS", "status must be one of " + List.of(Asset.Status.values()));
    }
    if (to == Asset.Status.BREAKDOWN) {
      return reportBreakdown(id, reason == null ? "Reported as broken down" : reason, "P2", "MANUAL", null);
    }
    Asset asset = require(id);
    move(asset, to, reason, "STATUS", null);
    return get(asset.getId());
  }

  /**
   * Marks the asset broken down and hands the breakdown to ticket-service through
   * {@code asset.breakdown.reported}; ticket-service opens the breakdown ticket and job card.
   */
  @Transactional
  public AssetView reportBreakdown(UUID id, String fault, String priority, String source, UUID pmTaskId) {
    Asset asset = require(id);
    String p = priority == null ? "P2" : priority.trim().toUpperCase(Locale.ROOT);
    if (!PRIORITIES.contains(p)) {
      throw ProblemException.badRequest("INVALID_PRIORITY", "priority must be one of " + PRIORITIES);
    }
    if (fault == null || fault.isBlank()) {
      throw ProblemException.badRequest("FAULT_REQUIRED", "Describe the fault");
    }
    if (asset.status() == Asset.Status.DISPOSED || asset.status() == Asset.Status.RETIRED) {
      throw ProblemException.unprocessable("ASSET_NOT_IN_SERVICE", "Asset " + asset.getAssetCode() + " is " + asset.getStatus());
    }
    move(asset, Asset.Status.BREAKDOWN, fault, "BREAKDOWN", null);
    history.record(asset.getId(), AssetHistory.Kind.BREAKDOWN, "Breakdown reported (" + source + "): " + fault);
    events.publish(new AssetEvents.BreakdownReported(asset.getId(), asset.getAssetCode(), asset.getName(),
        asset.getLocationId(), fault.trim(), p, source, pmTaskId, TenantContext.userId().orElse(null)));
    return get(asset.getId());
  }

  @Transactional
  public AssetView dispose(UUID id, String reason) {
    Asset asset = require(id);
    move(asset, Asset.Status.DISPOSED, reason, "DISPOSAL", null);
    history.record(asset.getId(), AssetHistory.Kind.DISPOSED, "Disposed" + (reason == null ? "" : ": " + reason));
    return get(asset.getId());
  }

  /** {@code ticket.breakdown.reported}: ticket-service logged a breakdown against this asset. */
  @Transactional
  public void onTicketBreakdown(UUID assetId, UUID breakdownId, String number, String priority) {
    Asset asset = assets.findById(assetId).orElse(null);
    if (asset == null || asset.status() == Asset.Status.DISPOSED || asset.status() == Asset.Status.RETIRED) {
      return;
    }
    if (asset.status() == Asset.Status.WORKING) {
      move(asset, Asset.Status.BREAKDOWN, "Breakdown " + number, "BREAKDOWN_TICKET", breakdownId);
    }
    history.record(assetId, AssetHistory.Kind.BREAKDOWN, "BREAKDOWN_TICKET", breakdownId,
        "Breakdown ticket " + number + " (" + priority + ")", 0, null);
  }

  /** A breakdown or complaint job card was opened: the asset is now being repaired. */
  @Transactional
  public void onRepairStarted(UUID assetId, UUID jobCardId, String number) {
    Asset asset = assets.findById(assetId).orElse(null);
    if (asset != null && asset.status() == Asset.Status.BREAKDOWN) {
      move(asset, Asset.Status.UNDER_REPAIR, "Job card " + number, "JOB_CARD", jobCardId);
    }
  }

  /** {@code ticket.jobcard.closed} (non-PM): repair on the timeline, cost rolled up, back to WORKING. */
  @Transactional
  public void onRepairClosed(UUID assetId, UUID jobCardId, String number, long costPaise, String rootCause, Instant at) {
    Asset asset = assets.findById(assetId).orElse(null);
    if (asset == null) {
      return;
    }
    String summary = "Job card " + number + " closed" + (rootCause == null || rootCause.isBlank() ? "" : ": " + rootCause);
    if (history.record(assetId, AssetHistory.Kind.REPAIR, "JOB_CARD", jobCardId, summary, costPaise, at)) {
      asset.addMaintenanceCost(costPaise);
    }
    if (asset.status() == Asset.Status.BREAKDOWN || asset.status() == Asset.Status.UNDER_REPAIR) {
      move(asset, Asset.Status.WORKING, summary, "JOB_CARD_CLOSED", jobCardId);
    }
  }

  private void move(Asset asset, Asset.Status to, String reason, String refType, UUID refId) {
    String from = asset.getStatus();
    boolean changed;
    try {
      changed = asset.changeStatus(to);
    } catch (IllegalStateException e) {
      throw ProblemException.unprocessable("INVALID_STATUS_CHANGE", e.getMessage());
    }
    if (!changed) {
      return;
    }
    assets.save(asset);
    history.record(asset.getId(), AssetHistory.Kind.STATUS_CHANGED, refType, refId,
        from + " → " + to + (reason == null || reason.isBlank() ? "" : ": " + reason), 0, null);
    events.publish(new AssetEvents.AssetStatusChanged(asset.getId(), asset.getAssetCode(), from, to.name()));
  }

  // --- QR ---------------------------------------------------------------------------------

  @Transactional
  public AssetView regenerateQr(UUID id) {
    Asset asset = require(id);
    try {
      asset.regenerateQr();
    } catch (IllegalStateException e) {
      throw ProblemException.unprocessable("ASSET_DISPOSED", e.getMessage());
    }
    history.record(asset.getId(), AssetHistory.Kind.QR, "QR code regenerated; old labels no longer work");
    return get(assets.save(asset).getId());
  }

  @Transactional(readOnly = true)
  public List<QrLabel> labels(Collection<UUID> ids) {
    List<Asset> found = ids == null || ids.isEmpty()
        ? assets.search(null, null, null, null, PageRequest.of(0, 500)) : assets.findAllByIdIn(ids);
    Map<UUID, String> locations = reference.locationNames(found.stream().map(Asset::getLocationId).toList());
    return found.stream().filter(a -> a.getQrToken() != null)
        .map(a -> new QrLabel(a.getId(), a.getAssetCode(), a.getName(), a.getCategory(),
            locations.get(a.getLocationId()), a.getQrToken(), qrBaseUrl + a.getQrToken()))
        .toList();
  }

  @Transactional
  public int markPrinted(Collection<UUID> ids) {
    Instant now = clock.instant();
    List<Asset> found = assets.findAllByIdIn(ids);
    found.forEach(a -> a.markQrPrinted(now));
    return found.size();
  }

  // --- helpers ----------------------------------------------------------------------------

  Asset require(UUID id) {
    return assets.findById(id).orElseThrow(() -> ProblemException.notFound("asset", id));
  }

  private List<AssetView> views(List<Asset> list) {
    Map<UUID, String> locations = reference.locationNames(list.stream().map(Asset::getLocationId).toList());
    Map<UUID, String> vendors = reference.vendorNames(list.stream().map(Asset::getVendorId).toList());
    return list.stream().map(a -> new AssetView(a, locations.get(a.getLocationId()), vendors.get(a.getVendorId()),
        parseSpec(a.getSpecJson()))).toList();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseSpec(String spec) {
    return spec == null ? Map.of() : json.readValue(spec, Map.class);
  }

  /** Coverage dates on the asset create warranty / AMC records so expiry alerts see them. */
  private void syncCoverage(Asset asset, LocalDate oldWarranty, LocalDate oldAmc) {
    LocalDate start = asset.getInstallationDate() != null ? asset.getInstallationDate() : reference.today();
    LocalDate w = asset.getWarrantyUntil();
    if (w != null && !w.equals(oldWarranty)
        && warranties.findByAssetIdOrderByEndsOnDesc(asset.getId()).stream().noneMatch(x -> x.getEndsOn().equals(w))) {
      Warranty saved = warranties.save(new Warranty(asset.getId(), asset.getVendorId(), start.isAfter(w) ? null : start,
          w, null, null));
      history.record(asset.getId(), AssetHistory.Kind.WARRANTY, "WARRANTY", saved.getId(), "Warranty until " + w, 0, null);
    }
    LocalDate a = asset.getAmcUntil();
    if (a != null && !a.equals(oldAmc)
        && amcs.findByAssetIdOrderByEndsOnDesc(asset.getId()).stream().noneMatch(x -> x.getEndsOn().equals(a))) {
      LocalDate amcStart = start.isAfter(a) ? a : start;
      AmcContract saved = amcs.save(new AmcContract(asset.getId(), new AmcContract.Details(asset.getVendorId(), null,
          amcStart, a, 0, 0, null, null)));
      history.record(asset.getId(), AssetHistory.Kind.AMC, "AMC", saved.getId(), "AMC until " + a, 0, null);
    }
  }

  private Asset.Details details(AssetInput in) {
    try {
      String category = in.category() == null ? null : in.category().trim().toUpperCase(Locale.ROOT);
      if (in.categoryId() != null) {
        var cat = categories.findById(in.categoryId())
            .orElseThrow(() -> ProblemException.notFound("asset_category", in.categoryId()));
        category = cat.getGroup();
      }
      String frequency = in.pmFrequency() == null || in.pmFrequency().isBlank() ? null
          : PmFrequency.parse(in.pmFrequency()).name();
      return new Asset.Details(in.name(), category, in.categoryId(), in.locationId(), clean(in.make()),
          clean(in.model()), clean(in.serialNo()), clean(in.manufacturer()), clean(in.supplier()), clean(in.engineNo()),
          clean(in.alternatorNo()), clean(in.capacity()), clean(in.maintenanceAgency()), in.vendorId(),
          in.photoMediaIds(), in.purchaseDate(), in.installationDate(), in.costPaise(), in.expectedLifeMonths(),
          in.warrantyUntil(), in.amcUntil(), frequency, in.spec() == null ? null : json.writeValueAsString(in.spec()));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_ASSET", e.getMessage());
    }
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
