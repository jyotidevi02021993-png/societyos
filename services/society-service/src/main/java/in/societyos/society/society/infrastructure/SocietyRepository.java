package in.societyos.society.society.infrastructure;

import in.societyos.society.society.domain.Society;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocietyRepository extends JpaRepository<Society, UUID> {
  List<Society> findAllByOrderByNameAsc();
}
