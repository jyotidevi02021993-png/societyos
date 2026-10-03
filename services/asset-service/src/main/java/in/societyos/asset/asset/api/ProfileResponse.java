package in.societyos.asset.asset.api;

import in.societyos.asset.asset.application.AssetProfileQuery;
import in.societyos.asset.coverage.api.CoverageController;
import in.societyos.asset.pm.api.PmPlanController;
import in.societyos.asset.pm.api.PmTaskController;
import java.util.List;

/** Response of the QR scan: everything a technician needs on one screen. */
public record ProfileResponse(
    AssetController.AssetResponse asset,
    List<PmPlanController.PlanResponse> pmPlans,
    List<PmTaskController.TaskSummary> openPmTasks,
    List<CoverageController.WarrantyResponse> warranties,
    List<CoverageController.AmcResponse> amcs,
    List<AssetController.HistoryResponse> recentHistory) {

  static ProfileResponse from(AssetProfileQuery.Profile p) {
    return new ProfileResponse(
        AssetController.AssetResponse.from(p.asset()),
        p.plans().stream().map(PmPlanController.PlanResponse::from).toList(),
        p.openTasks().stream().map(PmTaskController.TaskSummary::from).toList(),
        p.warranties().stream().map(CoverageController.WarrantyResponse::from).toList(),
        p.amcs().stream().map(CoverageController.AmcResponse::from).toList(),
        p.recentHistory().stream().map(AssetController.HistoryResponse::from).toList());
  }
}
