package in.societyos.asset.pm.infrastructure;

import in.societyos.asset.pm.domain.PmTask;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PmTaskRepository extends JpaRepository<PmTask, UUID> {
  List<PmTask> findByPmPlanIdAndStatusIn(UUID planId, Collection<PmTask.Status> statuses);
  boolean existsByPmPlanIdAndStatusIn(UUID planId, Collection<PmTask.Status> statuses);
  List<PmTask> findByAssetIdOrderByDueOnDesc(UUID assetId, Pageable page);
  List<PmTask> findByAssetIdAndStatusInOrderByDueOnAsc(UUID assetId, Collection<PmTask.Status> statuses);
  Optional<PmTask> findByJobCardId(UUID jobCardId);

  @Query("""
      select t from PmTask t
      where (:status is null or t.status = :status)
        and (:assetId is null or t.assetId = :assetId)
        and (:assignee is null or t.assigneeUserId = :assignee)
        and t.dueOn >= :from and t.dueOn <= :to
      order by t.dueOn asc, t.id asc
      """)
  List<PmTask> search(@Param("status") PmTask.Status status, @Param("assetId") UUID assetId,
      @Param("assignee") UUID assignee, @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable page);

  @Query("select t from PmTask t where t.status in :open and t.dueOn < :today and t.overdueNotified = false")
  List<PmTask> findNewlyOverdue(@Param("open") Collection<PmTask.Status> open, @Param("today") LocalDate today);

  List<PmTask> findByUpdatedAtAfterOrderByUpdatedAtAsc(Instant since, Pageable page);
}
