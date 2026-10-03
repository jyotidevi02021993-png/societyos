package in.societyos.asset.asset.api;

import in.societyos.asset.asset.application.AssetService;
import in.societyos.asset.pm.api.PmTaskController;
import in.societyos.asset.pm.application.PmTaskService;
import in.societyos.asset.platform.web.CursorPage;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mobile offline sync (pull): assets and PM tasks changed since the last sync. Offline pushes
 * reuse the normal endpoints with an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/v1/sync")
public class SyncController {

  private final AssetService assets;
  private final PmTaskService pmTasks;

  public SyncController(AssetService assets, PmTaskService pmTasks) {
    this.assets = assets;
    this.pmTasks = pmTasks;
  }

  public record Changes(Instant serverTime, List<AssetController.AssetResponse> assets,
      List<PmTaskController.TaskSummary> pmTasks) {}

  @GetMapping("/changes")
  @PreAuthorize("@perm.hasAny('asset:view', 'pm:execute')")
  public Changes changes(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
      @RequestParam(required = false) Integer limit) {
    Instant now = Instant.now();
    Instant from = since == null ? Instant.EPOCH : since;
    int max = CursorPage.clampLimit(limit);
    return new Changes(now,
        assets.changedSince(from, max).stream().map(AssetController.AssetResponse::from).toList(),
        pmTasks.changedSince(from, max).stream().map(PmTaskController.TaskSummary::from).toList());
  }
}
