package in.societyos.asset.asset.application;

import in.societyos.asset.asset.domain.AssetHistory;
import in.societyos.asset.coverage.application.CoverageService;
import in.societyos.asset.coverage.domain.AmcContract;
import in.societyos.asset.coverage.domain.Warranty;
import in.societyos.asset.pm.application.PmPlanService;
import in.societyos.asset.pm.application.PmTaskService;
import in.societyos.asset.pm.domain.PmTask;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The full profile a technician sees after scanning an asset's QR code. */
@Service
public class AssetProfileQuery {

  public record Profile(AssetService.AssetView asset, List<PmPlanService.PlanView> plans, List<PmTask> openTasks,
      List<Warranty> warranties, List<AmcContract> amcs, List<AssetHistory> recentHistory) {}

  private final AssetService assets;
  private final PmPlanService plans;
  private final PmTaskService tasks;
  private final CoverageService coverage;
  private final AssetHistoryService history;

  public AssetProfileQuery(AssetService assets, PmPlanService plans, PmTaskService tasks, CoverageService coverage,
      AssetHistoryService history) {
    this.assets = assets;
    this.plans = plans;
    this.tasks = tasks;
    this.coverage = coverage;
    this.history = history;
  }

  @Transactional(readOnly = true)
  public Profile byQr(String token) {
    return profile(assets.byQr(token));
  }

  @Transactional(readOnly = true)
  public Profile byId(UUID id) {
    return profile(assets.get(id));
  }

  private Profile profile(AssetService.AssetView view) {
    UUID id = view.asset().getId();
    return new Profile(view, plans.list(id), tasks.openForAsset(id), coverage.warranties(id), coverage.amcs(id),
        history.timeline(id, 20));
  }
}
