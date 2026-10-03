package in.societyos.asset.asset.infrastructure;

import in.societyos.asset.asset.domain.AssetHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetHistoryRepository extends JpaRepository<AssetHistory, UUID> {
  List<AssetHistory> findByAssetIdOrderByAtDescIdDesc(UUID assetId, Pageable page);
  boolean existsByAssetIdAndKindAndRefTypeAndRefId(UUID assetId, AssetHistory.Kind kind, String refType, UUID refId);
}
