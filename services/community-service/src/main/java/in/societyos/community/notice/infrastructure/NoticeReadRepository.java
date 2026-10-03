package in.societyos.community.notice.infrastructure;

import in.societyos.community.notice.domain.NoticeRead;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeReadRepository extends JpaRepository<NoticeRead, UUID> {

  /** Idempotent read receipt: a second read keeps the first time. */
  @Modifying
  @Query(value = """
      insert into notice_read (id, society_id, notice_id, user_id, read_at, created_at, created_by, updated_at, updated_by, version)
      values (:id, :society, :notice, :user, now(), now(), :user, now(), :user, 0)
      on conflict (notice_id, user_id) do nothing""", nativeQuery = true)
  int markRead(@Param("id") UUID id, @Param("society") UUID society, @Param("notice") UUID notice,
      @Param("user") UUID user);

  List<NoticeRead> findByNoticeIdOrderByReadAtAsc(UUID noticeId);

  long countByNoticeId(UUID noticeId);

  @Query("select r.noticeId from NoticeRead r where r.userId = :user and r.noticeId in :notices")
  List<UUID> readBy(@Param("user") UUID user, @Param("notices") Collection<UUID> notices);
}
