package in.societyos.media.media.application;

import in.societyos.media.media.domain.MediaEvents.FileProcessed;
import in.societyos.media.media.domain.MediaEvents.FileRejected;
import in.societyos.media.media.domain.MediaFile;
import in.societyos.media.media.domain.MediaStatus;
import in.societyos.media.media.infrastructure.MediaFileRepository;
import in.societyos.media.media.infrastructure.MediaSocietyScan;
import in.societyos.media.platform.core.UuidV7;
import in.societyos.media.platform.core.tenant.TenantContext;
import in.societyos.media.platform.events.DomainEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Work woken by db-scheduler (ADR-0004). Per society, one transaction per file:
 * <ul>
 *   <li>{@link #process}: UPLOADED files are virus-scanned and images get a thumbnail → READY or REJECTED;
 *   <li>{@link #purge}: unused upload slots expire, files past {@code retain_until} are deleted.
 * </ul>
 */
@Service
public class MediaJobs {

  private static final Logger log = LoggerFactory.getLogger(MediaJobs.class);

  private final MediaSocietyScan scan;
  private final MediaFileRepository files;
  private final ObjectStorage storage;
  private final VirusScanner scanner;
  private final Thumbnailer thumbnailer;
  private final MediaService media;
  private final DomainEvents events;
  private final MediaProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MediaJobs(MediaSocietyScan scan, MediaFileRepository files, ObjectStorage storage, VirusScanner scanner,
      Thumbnailer thumbnailer, MediaService media, DomainEvents events, MediaProperties props,
      PlatformTransactionManager txManager, Clock clock) {
    this.scan = scan;
    this.files = files;
    this.storage = storage;
    this.scanner = scanner;
    this.thumbnailer = thumbnailer;
    this.media = media;
    this.events = events;
    this.props = props;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  public void process() {
    for (UUID society : scan.withWork(clock.instant())) {
      TenantContext.runAs(society, () -> {
        List<UUID> ids = tx.execute(s -> files
            .findByStatusOrderByCreatedAtAsc(MediaStatus.UPLOADED, Limit.of(props.getBatchSize()))
            .stream().map(MediaFile::getId).toList());
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
          try {
            tx.executeWithoutResult(s -> files.findById(id)
                .filter(m -> m.getStatus() == MediaStatus.UPLOADED)
                .ifPresent(this::processOne));
          } catch (RuntimeException e) {
            log.warn("Processing media {} failed: {}", id, e.toString());
          }
        }
      });
    }
  }

  void processOne(MediaFile m) {
    Instant now = clock.instant();
    byte[] bytes = storage.read(m.getObjectKey(), m.getPurpose().maxBytes());
    if (scanner.scan(bytes, m.getContentType()) == VirusScanner.Verdict.INFECTED) {
      m.markInfected(now);
      storage.delete(m.getObjectKey());
      events.publish(new FileRejected(m.getId(), m.getOwnerService(), m.getRejectReason()));
      log.warn("Media {} rejected: virus detected", m.getId());
      return;
    }
    Integer width = null;
    Integer height = null;
    UUID thumbId = null;
    if (m.isImage()) {
      var thumb = thumbnailer.thumbnail(bytes, props.getThumbnailEdge()).orElse(null);
      if (thumb == null) {
        m.reject("UNREADABLE_IMAGE", now);
        storage.delete(m.getObjectKey());
        events.publish(new FileRejected(m.getId(), m.getOwnerService(), m.getRejectReason()));
        return;
      }
      MediaFile t = MediaFile.thumbnailOf(UuidV7.next(), m, thumb.jpeg().length, thumb.width(), thumb.height(), now);
      storage.write(t.getObjectKey(), thumb.jpeg(), t.getContentType());
      files.save(t);
      width = thumb.sourceWidth();
      height = thumb.sourceHeight();
      thumbId = t.getId();
    }
    m.markClean(width, height, thumbId, now);
    events.publish(new FileProcessed(m.getId(), m.getOwnerService(), m.getPurpose().name(), thumbId));
  }

  public void purge() {
    Instant now = clock.instant();
    for (UUID society : scan.withWork(now)) {
      TenantContext.runAs(society, () -> {
        try {
          tx.executeWithoutResult(s -> {
            files.findExpiredPending(now, Limit.of(500)).forEach(m -> {
              storage.delete(m.getObjectKey()); // a late PUT may have landed after the window
              m.expire();
            });
            List<MediaFile> due = files.findRetentionDue(now, Limit.of(500));
            due.forEach(m -> media.remove(m, "RETENTION"));
            if (!due.isEmpty()) {
              log.info("Retention purge deleted {} file(s)", due.size());
            }
          });
        } catch (RuntimeException e) {
          log.warn("Media purge failed for society {}: {}", society, e.toString());
        }
      });
    }
  }
}
