package in.societyos.asset.asset.infrastructure;

import in.societyos.asset.asset.domain.Asset;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
  Optional<Asset> findByAssetCode(String assetCode);
  Optional<Asset> findByQrToken(String qrToken);
  boolean existsByAssetCodeIgnoreCase(String assetCode);
  List<Asset> findAllByIdIn(Collection<UUID> ids);

  @Query("""
      select a from Asset a
      where (:category is null or a.category = :category)
        and (:status is null or a.status = :status)
        and (:locationId is null or a.locationId = :locationId)
        and (:q is null or lower(a.name) like :q or lower(a.assetCode) like :q or lower(coalesce(a.serialNo, 'x')) like :q)
      order by a.name asc, a.id asc
      """)
  List<Asset> search(@Param("category") String category, @Param("status") String status,
      @Param("locationId") UUID locationId, @Param("q") String q, Pageable page);

  List<Asset> findByUpdatedAtAfterOrderByUpdatedAtAsc(Instant since, Pageable page);
}
