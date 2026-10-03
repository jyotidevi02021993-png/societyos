package in.societyos.utility.signoff.infrastructure;

import in.societyos.utility.signoff.domain.ManagerSignOff;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerSignOffRepository extends JpaRepository<ManagerSignOff, UUID> {
  Optional<ManagerSignOff> findBySignDate(LocalDate date);
  List<ManagerSignOff> findBySignDateBetweenOrderBySignDateDesc(LocalDate from, LocalDate to);
}
