package in.societyos.asset.asset.domain;

import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** The asset master record (Req §8 and the Equipment Registration form). */
@Entity
@Table(name = "asset")
public class Asset extends TenantEntity {

  /** Category groups (doc 01 §5). */
  public static final Set<String> CATEGORY_GROUPS = Set.of("WATER", "ELECTRICAL", "MECHANICAL",
      "LIFT", "FIRE_SAFETY", "HVAC", "CIVIL", "CLUBHOUSE", "GYM", "GARDENING", "OTHER");

  public enum Status { WORKING, BREAKDOWN, UNDER_REPAIR, RETIRED, DISPOSED }

  /** Allowed status moves. DISPOSED is final. */
  private static final Map<Status, Set<Status>> MOVES = Map.of(
      Status.WORKING, EnumSet.of(Status.BREAKDOWN, Status.UNDER_REPAIR, Status.RETIRED, Status.DISPOSED),
      Status.BREAKDOWN, EnumSet.of(Status.UNDER_REPAIR, Status.WORKING, Status.RETIRED, Status.DISPOSED),
      Status.UNDER_REPAIR, EnumSet.of(Status.WORKING, Status.BREAKDOWN, Status.RETIRED, Status.DISPOSED),
      Status.RETIRED, EnumSet.of(Status.WORKING, Status.DISPOSED),
      Status.DISPOSED, EnumSet.noneOf(Status.class));

  private static final SecureRandom RANDOM = new SecureRandom();

  @Column(name = "asset_code", nullable = false, updatable = false)
  private String assetCode;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String category;

  @Column(name = "category_id")
  private UUID categoryId;

  @Column(name = "location_id")
  private UUID locationId;

  private String make;
  private String model;

  @Column(name = "serial_no")
  private String serialNo;

  private String manufacturer;
  private String supplier;

  @Column(name = "engine_no")
  private String engineNo;

  @Column(name = "alternator_no")
  private String alternatorNo;

  private String capacity;

  @Column(name = "maintenance_agency")
  private String maintenanceAgency;

  @Column(name = "vendor_id")
  private UUID vendorId;

  @Column(name = "photo_media_id")
  private UUID photoMediaId;

  @Column(name = "photo_media_ids", nullable = false, columnDefinition = "uuid[]")
  private UUID[] photoMediaIds;

  @Column(name = "purchase_date")
  private LocalDate purchaseDate;

  @Column(name = "installation_date")
  private LocalDate installationDate;

  @Column(name = "cost_paise")
  private Long costPaise;

  @Column(name = "expected_life_months")
  private Integer expectedLifeMonths;

  @Column(name = "warranty_until")
  private LocalDate warrantyUntil;

  @Column(name = "amc_until")
  private LocalDate amcUntil;

  @Column(name = "pm_frequency")
  private String pmFrequency;

  @Column(name = "spec", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String specJson;

  @Column(nullable = false)
  private String status;

  @Column(name = "qr_token")
  private String qrToken;

  @Column(name = "qr_printed_at")
  private Instant qrPrintedAt;

  @Column(name = "breakdown_count", nullable = false)
  private int breakdownCount;

  @Column(name = "total_maintenance_cost_paise", nullable = false)
  private long totalMaintenanceCostPaise;

  @Column(name = "disposed_on")
  private LocalDate disposedOn;

  protected Asset() {}

  /** The editable master data. */
  public record Details(
      String name, String category, UUID categoryId, UUID locationId, String make, String model,
      String serialNo, String manufacturer, String supplier, String engineNo, String alternatorNo,
      String capacity, String maintenanceAgency, UUID vendorId, List<UUID> photoMediaIds,
      LocalDate purchaseDate, LocalDate installationDate, Long costPaise, Integer expectedLifeMonths,
      LocalDate warrantyUntil, LocalDate amcUntil, String pmFrequency, String specJson) {

    public Details {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("name is required");
      }
      if (category == null || !CATEGORY_GROUPS.contains(category)) {
        throw new IllegalArgumentException("category must be one of " + CATEGORY_GROUPS);
      }
      if (costPaise != null && costPaise < 0) {
        throw new IllegalArgumentException("costPaise cannot be negative");
      }
      if (installationDate != null && warrantyUntil != null && warrantyUntil.isBefore(installationDate)) {
        throw new IllegalArgumentException("warrantyUntil cannot be before installationDate");
      }
      if (installationDate != null && amcUntil != null && amcUntil.isBefore(installationDate)) {
        throw new IllegalArgumentException("amcUntil cannot be before installationDate");
      }
      if (purchaseDate != null && installationDate != null && installationDate.isBefore(purchaseDate)) {
        throw new IllegalArgumentException("installationDate cannot be before purchaseDate");
      }
      photoMediaIds = photoMediaIds == null ? List.of() : List.copyOf(photoMediaIds);
      specJson = specJson == null || specJson.isBlank() ? "{}" : specJson;
    }
  }

  public Asset(String assetCode, Details d) {
    super(UuidV7.next());
    this.assetCode = assetCode;
    this.status = Status.WORKING.name();
    this.qrToken = newQrToken();
    update(d);
  }

  public void update(Details d) {
    this.name = d.name().trim();
    this.category = d.category();
    this.categoryId = d.categoryId();
    this.locationId = d.locationId();
    this.make = d.make();
    this.model = d.model();
    this.serialNo = d.serialNo();
    this.manufacturer = d.manufacturer();
    this.supplier = d.supplier();
    this.engineNo = d.engineNo();
    this.alternatorNo = d.alternatorNo();
    this.capacity = d.capacity();
    this.maintenanceAgency = d.maintenanceAgency();
    this.vendorId = d.vendorId();
    this.photoMediaIds = d.photoMediaIds().toArray(UUID[]::new);
    this.photoMediaId = d.photoMediaIds().isEmpty() ? null : d.photoMediaIds().getFirst();
    this.purchaseDate = d.purchaseDate();
    this.installationDate = d.installationDate();
    this.costPaise = d.costPaise();
    this.expectedLifeMonths = d.expectedLifeMonths();
    this.warrantyUntil = d.warrantyUntil();
    this.amcUntil = d.amcUntil();
    this.pmFrequency = d.pmFrequency();
    this.specJson = d.specJson();
  }

  /** Moves the status; returns false when it already had that status. */
  public boolean changeStatus(Status to) {
    Status from = Status.valueOf(status);
    if (from == to) {
      return false;
    }
    if (!MOVES.get(from).contains(to)) {
      throw new IllegalStateException("Asset " + assetCode + " cannot go from " + from + " to " + to);
    }
    if (to == Status.BREAKDOWN) {
      breakdownCount++;
    }
    if (to == Status.DISPOSED) {
      disposedOn = LocalDate.now();
      qrToken = null;
    }
    this.status = to.name();
    return true;
  }

  public void addMaintenanceCost(long paise) {
    if (paise > 0) {
      totalMaintenanceCostPaise += paise;
    }
  }

  /** Coverage dates follow the latest warranty / AMC record. */
  public void coverWarrantyUntil(LocalDate endsOn) {
    if (warrantyUntil == null || endsOn.isAfter(warrantyUntil)) {
      warrantyUntil = endsOn;
    }
  }

  public void coverAmcUntil(LocalDate endsOn) {
    if (amcUntil == null || endsOn.isAfter(amcUntil)) {
      amcUntil = endsOn;
    }
  }

  public void regenerateQr() {
    if (Status.valueOf(status) == Status.DISPOSED) {
      throw new IllegalStateException("A disposed asset has no QR code");
    }
    qrToken = newQrToken();
    qrPrintedAt = null;
  }

  public void markQrPrinted(Instant at) {
    qrPrintedAt = at;
  }

  public void addPhoto(UUID mediaId) {
    UUID[] current = photoMediaIds == null ? new UUID[0] : photoMediaIds;
    for (UUID id : current) {
      if (id.equals(mediaId)) {
        return;
      }
    }
    UUID[] next = java.util.Arrays.copyOf(current, current.length + 1);
    next[current.length] = mediaId;
    photoMediaIds = next;
    if (photoMediaId == null) {
      photoMediaId = mediaId;
    }
  }

  /** 128-bit random, URL-safe: printed on the label, never guessable from the asset code. */
  static String newQrToken() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public Status status() { return Status.valueOf(status); }

  public String getAssetCode() { return assetCode; }
  public String getName() { return name; }
  public String getCategory() { return category; }
  public UUID getCategoryId() { return categoryId; }
  public UUID getLocationId() { return locationId; }
  public String getMake() { return make; }
  public String getModel() { return model; }
  public String getSerialNo() { return serialNo; }
  public String getManufacturer() { return manufacturer; }
  public String getSupplier() { return supplier; }
  public String getEngineNo() { return engineNo; }
  public String getAlternatorNo() { return alternatorNo; }
  public String getCapacity() { return capacity; }
  public String getMaintenanceAgency() { return maintenanceAgency; }
  public UUID getVendorId() { return vendorId; }
  public UUID getPhotoMediaId() { return photoMediaId; }
  public List<UUID> getPhotoMediaIds() { return photoMediaIds == null ? List.of() : List.of(photoMediaIds); }
  public LocalDate getPurchaseDate() { return purchaseDate; }
  public LocalDate getInstallationDate() { return installationDate; }
  public Long getCostPaise() { return costPaise; }
  public Integer getExpectedLifeMonths() { return expectedLifeMonths; }
  public LocalDate getWarrantyUntil() { return warrantyUntil; }
  public LocalDate getAmcUntil() { return amcUntil; }
  public String getPmFrequency() { return pmFrequency; }
  public String getSpecJson() { return specJson; }
  public String getStatus() { return status; }
  public String getQrToken() { return qrToken; }
  public Instant getQrPrintedAt() { return qrPrintedAt; }
  public int getBreakdownCount() { return breakdownCount; }
  public long getTotalMaintenanceCostPaise() { return totalMaintenanceCostPaise; }
  public LocalDate getDisposedOn() { return disposedOn; }
}
