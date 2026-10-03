package in.societyos.asset.asset.infrastructure;

import in.societyos.asset.asset.domain.AssetCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetCategoryRepository extends JpaRepository<AssetCategory, UUID> {
  List<AssetCategory> findAllByOrderByGroupAscNameAsc();
  boolean existsByCodeIgnoreCase(String code);
}
