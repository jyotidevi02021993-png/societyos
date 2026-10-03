package in.societyos.media.media.application;

import in.societyos.media.media.application.MediaViews.DownloadLink;
import in.societyos.media.media.application.MediaViews.MediaView;
import in.societyos.media.media.application.MediaViews.UploadTicket;
import in.societyos.media.media.domain.MediaEvents.FileDeleted;
import in.societyos.media.media.domain.MediaEvents.FileRejected;
import in.societyos.media.media.domain.MediaEvents.FileUploaded;
import in.societyos.media.media.domain.MediaFile;
import in.societyos.media.media.domain.MediaPurpose;
import in.societyos.media.media.domain.MediaRuleException;
import in.societyos.media.media.domain.MediaStatus;
import in.societyos.media.media.infrastructure.MediaFileRepository;
import in.societyos.media.platform.core.UuidV7;
import in.societyos.media.platform.core.error.ProblemException;
import in.societyos.media.platform.core.tenant.TenantContext;
import in.societyos.media.platform.events.DomainEvents;
import in.societyos.media.platform.security.PermissionEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upload and download use cases. Row-level security confines every lookup to the caller's
 * society, so a media id from another society is simply "not found".
 */
@Service
public class MediaService {

  public static final String MANAGE = "media:manage";

  private final MediaFileRepository files;
  private final ObjectStorage storage;
  private final RetentionSettings retention;
  private final DomainEvents events;
  private final PermissionEvaluator perm;
  private final MediaProperties props;
  private final Clock clock;

  public MediaService(MediaFileRepository files, ObjectStorage storage, RetentionSettings retention,
      DomainEvents events, PermissionEvaluator perm, MediaProperties props, Clock clock) {
    this.files = files;
    this.storage = storage;
    this.retention = retention;
    this.events = events;
    this.perm = perm;
    this.props = props;
    this.clock = clock;
  }

  public record UploadRequest(String purpose, String contentType, long sizeBytes, String fileName) {}

  /** Creates a PENDING record and a presigned PUT (15 min, one key, content type and size fixed). */
  @Transactional
  public UploadTicket requestUpload(UploadRequest req) {
    MediaPurpose purpose = purpose(req.purpose());
    UUID societyId = TenantContext.activeSocietyId();
    Instant now = clock.instant();
    MediaFile media;
    try {
      media = MediaFile.requestUpload(UuidV7.next(), societyId, purpose, req.contentType(), req.sizeBytes(),
          req.fileName(), now, props.getUploadTtl());
    } catch (MediaRuleException e) {
      throw new ProblemException(e.code(), HttpStatus.BAD_REQUEST, e.getMessage());
    }
    files.save(media);
    var p = storage.presignPut(media.getObjectKey(), media.getContentType(), media.getDeclaredSize(),
        props.getUploadTtl());
    return new UploadTicket(media.getId(), p.url(), p.method(), p.headers(), p.expiresAt(), purpose.maxBytes());
  }

  /** The client finished its PUT: verify the object (HEAD) and move to UPLOADED, or reject. */
  @Transactional
  public MediaView complete(UUID id) {
    MediaFile media = find(id);
    if (media.getStatus() != MediaStatus.PENDING) {
      return MediaView.of(media);
    }
    if (!media.deletableBy(TenantContext.userId().orElse(null), false)) {
      throw ProblemException.forbidden("NOT_UPLOADER", "Only the uploader can complete this upload");
    }
    var info = storage.head(media.getObjectKey())
        .orElseThrow(() -> ProblemException.conflict("OBJECT_NOT_UPLOADED", "No object has been uploaded yet"));
    try {
      media.confirmUpload(info.sizeBytes(), info.contentType(), clock.instant(), retention.retentionFor(media.getPurpose()));
    } catch (MediaRuleException e) {
      throw ProblemException.conflict(e.code(), e.getMessage());
    }
    if (media.getStatus() == MediaStatus.REJECTED) {
      storage.delete(media.getObjectKey());
      events.publish(new FileRejected(media.getId(), media.getOwnerService(), media.getRejectReason()));
    } else {
      events.publish(new FileUploaded(media.getId(), media.getOwnerService(), media.getPurpose().name(),
          media.getContentType(), media.getSizeBytes()));
    }
    return MediaView.of(media);
  }

  @Transactional(readOnly = true)
  public MediaView get(UUID id) {
    MediaFile media = find(id);
    requireReadable(media);
    return MediaView.of(media);
  }

  /** A signed, short-lived GET URL, after the society (RLS) and purpose checks. */
  @Transactional(readOnly = true)
  public DownloadLink downloadLink(UUID id, String variant) {
    MediaFile media = find(id);
    requireReadable(media);
    if (media.getStatus() != MediaStatus.READY) {
      throw ProblemException.conflict("MEDIA_NOT_READY", "Media is " + media.getStatus());
    }
    MediaFile target = media;
    if ("thumbnail".equalsIgnoreCase(variant)) {
      if (media.getThumbnailId() == null) {
        throw ProblemException.notFound("thumbnail", id);
      }
      target = find(media.getThumbnailId());
    } else if (variant != null && !variant.isBlank() && !"original".equalsIgnoreCase(variant)) {
      throw ProblemException.badRequest("INVALID_VARIANT", "variant is original or thumbnail");
    }
    var p = storage.presignGet(target.getObjectKey(), target.getContentType(), media.getFileName(),
        props.getDownloadTtl());
    return new DownloadLink(target.getId(), p.url(), target.getContentType(), p.expiresAt());
  }

  /** Owner (uploader) or media:manage. Removes the objects; the row stays as a DELETED tombstone. */
  @Transactional
  public void delete(UUID id) {
    MediaFile media = find(id);
    if (!media.deletableBy(TenantContext.userId().orElse(null), perm.has(MANAGE))) {
      throw ProblemException.forbidden("MEDIA_FORBIDDEN", "Only the uploader or a media manager can delete");
    }
    remove(media, "OWNER_DELETE");
  }

  /** Shared with the retention job: delete objects of the file and its thumbnails, then tombstone. */
  void remove(MediaFile media, String reason) {
    if (media.getStatus() == MediaStatus.DELETED) {
      return;
    }
    Instant now = clock.instant();
    for (MediaFile child : files.findByParentId(media.getId())) {
      storage.delete(child.getObjectKey());
      child.delete(now);
    }
    storage.delete(media.getObjectKey());
    media.delete(now);
    events.publish(new FileDeleted(media.getId(), media.getOwnerService(), media.getPurpose().name(), reason));
  }

  private void requireReadable(MediaFile media) {
    if (media.getStatus() == MediaStatus.DELETED) {
      throw ProblemException.notFound("media", media.getId());
    }
    if (!media.readableBy(TenantContext.userId().orElse(null), perm.has(MANAGE))) {
      throw ProblemException.forbidden("MEDIA_FORBIDDEN", "This file is private to its uploader");
    }
  }

  private MediaFile find(UUID id) {
    return files.findById(id).orElseThrow(() -> ProblemException.notFound("media", id));
  }

  private static MediaPurpose purpose(String value) {
    try {
      return MediaPurpose.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_PURPOSE", "Unknown purpose " + value);
    }
  }
}
