package in.societyos.inventory.store.infrastructure;

import in.societyos.inventory.store.domain.Store;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, UUID> {

  boolean existsByCode(String code);

  Optional<Store> findByDefaultStoreTrue();

  List<Store> findAllByOrderByCodeAsc();
}
