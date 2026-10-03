package in.societyos.vendor.rfq.infrastructure;

import in.societyos.vendor.rfq.domain.Rfq;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RfqRepository extends JpaRepository<Rfq, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Rfq r where r.id = :id")
  Optional<Rfq> findForUpdate(UUID id);

  List<Rfq> findTop200ByOrderByCreatedAtDesc();

  List<Rfq> findTop200ByStatusOrderByCreatedAtDesc(Rfq.Status status);
}
