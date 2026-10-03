package in.societyos.media.media.infrastructure;

import in.societyos.media.media.domain.MediaFile;
import in.societyos.media.media.domain.MediaStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** RLS scopes every query to the bound society. */
public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

  List<MediaFile> findByStatusOrderByCreatedAtAsc(MediaStatus status, Limit limit);

  @Query("select m from MediaFile m where m.status = 'PENDING' and m.uploadExpiresAt < :now order by m.createdAt")
  List<MediaFile> findExpiredPending(Instant now, Limit limit);

  @Query("select m from MediaFile m where m.status = 'READY' and m.retainUntil < :now and m.parentId is null order by m.retainUntil")
  List<MediaFile> findRetentionDue(Instant now, Limit limit);

  List<MediaFile> findByParentId(UUID parentId);
}
