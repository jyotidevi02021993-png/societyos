package in.societyos.asset.asset.application;

import in.societyos.asset.asset.domain.AssetHistory;
import in.societyos.asset.asset.infrastructure.AssetHistoryRepository;
import in.societyos.asset.platform.core.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends to an asset's timeline. A line with a reference is written at most once. */
@Service
public class AssetHistoryService {

  private final AssetHistoryRepository history;
  private final Clock clock;

  public AssetHistoryService(AssetHistoryRepository history, Clock clock) {
    this.history = history;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean record(UUID assetId, AssetHistory.Kind kind, String refType, UUID refId, String summary,
      long costPaise, Instant at) {
    if (refId != null && history.existsByAssetIdAndKindAndRefTypeAndRefId(assetId, kind, refType, refId)) {
      return false;
    }
    history.save(new AssetHistory(assetId, at == null ? clock.instant() : at, kind, refType, refId, summary,
        costPaise, TenantContext.userId().orElse(null)));
    return true;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID assetId, AssetHistory.Kind kind, String summary) {
    record(assetId, kind, null, null, summary, 0, null);
  }

  @Transactional(readOnly = true)
  public List<AssetHistory> timeline(UUID assetId, int limit) {
    return history.findByAssetIdOrderByAtDescIdDesc(assetId, PageRequest.of(0, limit));
  }
}
