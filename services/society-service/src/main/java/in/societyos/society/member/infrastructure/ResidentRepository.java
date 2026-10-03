package in.societyos.society.member.infrastructure;

import in.societyos.society.member.domain.Resident;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResidentRepository extends JpaRepository<Resident, UUID> {
  Optional<Resident> findByUserId(UUID userId);

  List<Resident> findByIdIn(Collection<UUID> ids);

  List<Resident> findByDirectoryOptInTrueOrderByNameAsc();
}
