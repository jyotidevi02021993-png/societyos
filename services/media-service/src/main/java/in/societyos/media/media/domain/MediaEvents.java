package in.societyos.media.media.domain;

import in.societyos.media.platform.events.DomainEvent;
import java.util.UUID;

/** Events on {@code sos.media.events.v1}. No file names, no uploader identities beyond the envelope actor. */
public final class MediaEvents {

  private MediaEvents() {}

  public record FileUploaded(UUID mediaId, String ownerService, String purpose, String contentType, long sizeBytes)
      implements DomainEvent {
    public String type() { return "media.file.uploaded"; }
    public String context() { return "media"; }
    public UUID aggregateId() { return mediaId; }
  }

  public record FileProcessed(UUID mediaId, String ownerService, String purpose, UUID thumbnailMediaId)
      implements DomainEvent {
    public String type() { return "media.file.processed"; }
    public String context() { return "media"; }
    public UUID aggregateId() { return mediaId; }
  }

  public record FileRejected(UUID mediaId, String ownerService, String reason) implements DomainEvent {
    public String type() { return "media.file.rejected"; }
    public String context() { return "media"; }
    public UUID aggregateId() { return mediaId; }
  }

  /** Not yet in the catalogue: emitted on owner delete and on retention purge. */
  public record FileDeleted(UUID mediaId, String ownerService, String purpose, String reason) implements DomainEvent {
    public String type() { return "media.file.deleted"; }
    public String context() { return "media"; }
    public UUID aggregateId() { return mediaId; }
  }
}
