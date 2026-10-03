package in.societyos.inventory.item.infrastructure;

import in.societyos.inventory.item.domain.Item;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, UUID> {

  boolean existsByCode(String code);

  Optional<Item> findByCode(String code);

  List<Item> findAllByOrderByNameAsc();

  List<Item> findByCategoryOrderByNameAsc(String category);
}
