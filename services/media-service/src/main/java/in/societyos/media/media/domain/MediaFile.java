package in.societyos.media.media.domain;

import in.societyos.media.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/** One stored object. The state machine and access rule live here; S3 calls live in the application layer. */
@Entity
@Table(name = "media_file")
public class MediaFile extends TenantEntity {

  @Column(name = "parent_id", updatable = false)
  private UUID parentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private MediaPurpose purpose;

  @Column(name = "owner_service", nullable = false, updatable = false)
  private String ownerService;

  @Column(name = "content_type", nullable = false)
  private String contentType;

  @Column(name = "declared_size", nullable = false, updatable = false)
  private long declaredSize;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column(name = "file_name")
  private String fileName;

  @Column(name = "object_key", nullable = false, updatable = false)
  private String objectKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MediaStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "scan_status", nullable = false)
  private ScanStatus scanStatus;

  @Column(name = "reject_reason")
  private String rejectReason;

  private Integer width;
  private Integer height;

  @Column(name = "thumbnail_id")
  private UUID thumbnailId;

  @Column(name = "upload_expires_at", nullable = false)
  private Instant uploadExpiresAt;

  @Column(name = "uploaded_at")
  private Instant uploadedAt;

  @Column(name = "processed_at")
  private Instant processedAt;

  @Column(name = "retain_until")
  private Instant retainUntil;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected MediaFile() {}

  private MediaFile(UUID id, UUID societyId, MediaPurpose purpose, String contentType, long declaredSize,
      String fileName, Instant uploadExpiresAt) {
    super(id);
    this.purpose = purpose;
    this.ownerService = purpose.ownerService();
    this.contentType = MediaPurpose.normaliseContentType(contentType);
    this.declaredSize = declaredSize;
    this.fileName = sanitiseFileName(fileName);
    this.objectKey = objectKey(societyId, purpose, id, uploadExpiresAt);
    this.status = MediaStatus.PENDING;
    this.scanStatus = ScanStatus.PENDING;
    this.uploadExpiresAt = uploadExpiresAt;
  }

  /** A new upload slot; throws {@link MediaRuleException} when the purpose does not allow it. */
  public static MediaFile requestUpload(UUID id, UUID societyId, MediaPurpose purpose, String contentType,
      long declaredSize, String fileName, Instant now, Duration uploadWindow) {
    String violation = purpose.violation(contentType, declaredSize);
    if (violation != null) {
      throw new MediaRuleException(violation, "Upload not allowed for " + purpose + ": " + violation);
    }
    return new MediaFile(id, societyId, purpose, contentType, declaredSize, fileName, now.plus(uploadWindow));
  }

  /** A thumbnail generated from {@code parent}; already clean and ready. */
  public static MediaFile thumbnailOf(UUID id, MediaFile parent, long sizeBytes, int width, int height, Instant now) {
    MediaFile t = new MediaFile(id, parent.getSocietyId(), MediaPurpose.THUMBNAIL, "image/jpeg", sizeBytes, null, now);
    t.parentId = parent.getId();
    t.sizeBytes = sizeBytes;
    t.width = width;
    t.height = height;
    t.status = MediaStatus.READY;
    t.scanStatus = ScanStatus.SKIPPED;
    t.uploadedAt = now;
    t.processedAt = now;
    t.retainUntil = parent.retainUntil;
    return t;
  }

  /**
   * The client says the PUT finished; {@code actualSize}/{@code actualType} come from a HEAD on the
   * object. A mismatch rejects the file (the caller then deletes the object).
   */
  public void confirmUpload(long actualSize, String actualType, Instant now, Duration retention) {
    if (status == MediaStatus.UPLOADED || status == MediaStatus.READY) {
      return; // idempotent
    }
    if (status != MediaStatus.PENDING) {
      throw new MediaRuleException("MEDIA_NOT_PENDING", "Media is " + status);
    }
    if (now.isAfter(uploadExpiresAt)) {
      throw new MediaRuleException("UPLOAD_WINDOW_EXPIRED", "The upload URL has expired");
    }
    this.sizeBytes = actualSize;
    this.uploadedAt = now;
    this.retainUntil = retention == null ? null : now.plus(retention);
    if (actualSize > declaredSize || actualSize > purpose.maxBytes()) {
      reject("SIZE_MISMATCH", now);
      return;
    }
    if (!MediaPurpose.normaliseContentType(actualType).equals(contentType)) {
      reject("CONTENT_TYPE_MISMATCH", now);
      return;
    }
    this.status = MediaStatus.UPLOADED;
  }

  public void markClean(Integer width, Integer height, UUID thumbnailId, Instant now) {
    requireStatus(MediaStatus.UPLOADED);
    this.scanStatus = ScanStatus.CLEAN;
    this.width = width;
    this.height = height;
    this.thumbnailId = thumbnailId;
    this.status = MediaStatus.READY;
    this.processedAt = now;
  }

  public void markInfected(Instant now) {
    requireStatus(MediaStatus.UPLOADED);
    this.scanStatus = ScanStatus.INFECTED;
    reject("VIRUS_DETECTED", now);
  }

  public void reject(String reason, Instant now) {
    this.status = MediaStatus.REJECTED;
    this.rejectReason = reason;
    this.processedAt = now;
  }

  public void expire() {
    requireStatus(MediaStatus.PENDING);
    this.status = MediaStatus.EXPIRED;
  }

  public void delete(Instant now) {
    if (status == MediaStatus.DELETED) {
      return;
    }
    this.status = MediaStatus.DELETED;
    this.deletedAt = now;
  }

  /** Whether {@code userId} may download: society members may, private purposes only their uploader or a manager. */
  public boolean readableBy(UUID userId, boolean canManage) {
    if (canManage || !purpose.privateToUploader()) {
      return true;
    }
    return userId != null && userId.equals(getCreatedBy());
  }

  /** Only the uploader or a media manager may delete. */
  public boolean deletableBy(UUID userId, boolean canManage) {
    return canManage || (userId != null && userId.equals(getCreatedBy()));
  }

  public boolean isRetentionDue(Instant now) {
    return status == MediaStatus.READY && retainUntil != null && retainUntil.isBefore(now);
  }

  public boolean isUploadWindowOver(Instant now) {
    return status == MediaStatus.PENDING && uploadExpiresAt.isBefore(now);
  }

  public boolean isImage() {
    return contentType.startsWith("image/");
  }

  private void requireStatus(MediaStatus expected) {
    if (status != expected) {
      throw new MediaRuleException("MEDIA_STATE", "Media is " + status + ", expected " + expected);
    }
  }

  /** {@code <society>/<purpose>/<yyyy>/<MM>/<id>}: the society prefix lets IAM scope a service to its tenants. */
  static String objectKey(UUID societyId, MediaPurpose purpose, UUID id, Instant at) {
    ZonedDateTime t = at.atZone(ZoneOffset.UTC);
    return "%s/%s/%04d/%02d/%s".formatted(societyId, purpose.name().toLowerCase(), t.getYear(), t.getMonthValue(), id);
  }

  static String sanitiseFileName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String base = name.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
    return base.length() > 120 ? base.substring(base.length() - 120) : base;
  }

  public UUID getParentId() { return parentId; }
  public MediaPurpose getPurpose() { return purpose; }
  public String getOwnerService() { return ownerService; }
  public String getContentType() { return contentType; }
  public long getDeclaredSize() { return declaredSize; }
  public Long getSizeBytes() { return sizeBytes; }
  public String getFileName() { return fileName; }
  public String getObjectKey() { return objectKey; }
  public MediaStatus getStatus() { return status; }
  public ScanStatus getScanStatus() { return scanStatus; }
  public String getRejectReason() { return rejectReason; }
  public Integer getWidth() { return width; }
  public Integer getHeight() { return height; }
  public UUID getThumbnailId() { return thumbnailId; }
  public Instant getUploadExpiresAt() { return uploadExpiresAt; }
  public Instant getUploadedAt() { return uploadedAt; }
  public Instant getProcessedAt() { return processedAt; }
  public Instant getRetainUntil() { return retainUntil; }
  public Instant getDeletedAt() { return deletedAt; }
}
