package in.societyos.ticket.category.infrastructure;

import in.societyos.ticket.category.domain.TicketCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TicketCategoryRepository extends JpaRepository<TicketCategory, UUID> {

  List<TicketCategory> findAllByOrderByNameAsc();

  @Query("select c from TicketCategory c where lower(c.name) = lower(?1)")
  Optional<TicketCategory> findByNameIgnoreCase(String name);
}
