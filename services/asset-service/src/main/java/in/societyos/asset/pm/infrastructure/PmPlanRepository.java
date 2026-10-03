package in.societyos.asset.pm.infrastructure;

import in.societyos.asset.pm.domain.PmPlan;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PmPlanRepository extends JpaRepository<PmPlan, UUID> {
  List<PmPlan> findByAssetIdOrderByNameAsc(UUID assetId);
  List<PmPlan> findAllByOrderByNextDueOnAscNameAsc();

  /** Calendar plans whose next occurrence is inside its lead window by {@code today}. */
  @Query(value = """
      select * from pm_plan
      where active and next_due_on is not null and next_due_on - lead_days <= :today
      order by next_due_on, id
      for update skip locked
      """, nativeQuery = true)
  List<PmPlan> lockGeneratable(@Param("today") LocalDate today);

  List<PmPlan> findByAssetIdAndUsageMetricAndActiveTrue(UUID assetId, String usageMetric);
}
