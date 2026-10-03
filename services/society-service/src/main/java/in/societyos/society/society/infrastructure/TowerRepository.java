package in.societyos.society.society.infrastructure;

import in.societyos.society.society.domain.Tower;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TowerRepository extends JpaRepository<Tower, UUID> {
  Optional<Tower> findByCodeIgnoreCase(String code);

  List<Tower> findAllByOrderByCodeAsc();
}
