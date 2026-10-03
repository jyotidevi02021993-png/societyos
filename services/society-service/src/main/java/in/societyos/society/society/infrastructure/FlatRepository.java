package in.societyos.society.society.infrastructure;

import in.societyos.society.society.domain.Flat;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FlatRepository extends JpaRepository<Flat, UUID> {
  List<Flat> findAllByOrderByLabelAsc();

  List<Flat> findByTowerIdOrderByNumberAsc(UUID towerId);

  List<Flat> findByIdIn(Collection<UUID> ids);

  Optional<Flat> findByLabelIgnoreCase(String label);

  boolean existsByTowerIdAndNumberIgnoreCase(UUID towerId, String number);

  boolean existsByTowerId(UUID towerId);

  @Query("select max(f.floor) from Flat f where f.towerId = :towerId")
  Optional<Integer> maxFloor(UUID towerId);
}
