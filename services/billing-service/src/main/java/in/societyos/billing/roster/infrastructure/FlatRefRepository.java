package in.societyos.billing.roster.infrastructure;

import in.societyos.billing.roster.domain.FlatRef;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatRefRepository extends JpaRepository<FlatRef, UUID> {

  List<FlatRef> findAllByOrderByLabelAsc();

  List<FlatRef> findByIdInOrderByLabelAsc(Collection<UUID> ids);
}
