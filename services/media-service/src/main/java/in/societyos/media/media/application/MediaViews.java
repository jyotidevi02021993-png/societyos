package in.societyos.media.media.application;

import in.societyos.media.media.domain.MediaFile;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read models returned by the use cases (controllers expose them as JSON). */
public final class MediaViews {

  private MediaViews() {}

  public record MediaView(
      UUID id,
      String purpose,
      String ownerService,
      String contentType,
      long declaredSize,
      Long sizeBytes,
      String fileName,
      String status,
      String scanStatus,
      String rejectReason,
      Integer width,
      Integer height,
      UUID thumbnailMediaId,
      UUID parentMediaId,
      Instant uploadedAt,
      Instant processedAt,
      Instant retainUntil,
      Instant createdAt,
      UUID createdBy) {

    public static MediaView of(MediaFile m) {
      return new MediaView(m.getId(), m.getPurpose().name(), m.getOwnerService(), m.getContentType(),
          m.getDeclaredSize(), m.getSizeBytes(), m.getFileName(), m.getStatus().name(), m.getScanStatus().name(),
          m.getRejectReason(), m.getWidth(), m.getHeight(), m.getThumbnailId(), m.getParentId(), m.getUploadedAt(),
          m.getProcessedAt(), m.getRetainUntil(), m.getCreatedAt(), m.getCreatedBy());
    }
  }

  /** What the client needs to PUT the bytes straight to storage. */
  public record UploadTicket(UUID mediaId, URI uploadUrl, String method, Map<String, String> headers,
      Instant expiresAt, long maxBytes) {}

  public record DownloadLink(UUID mediaId, URI url, String contentType, Instant expiresAt) {}
}
