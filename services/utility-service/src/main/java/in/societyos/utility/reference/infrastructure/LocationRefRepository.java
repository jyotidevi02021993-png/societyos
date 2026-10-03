package in.societyos.utility.reference.infrastructure;

import in.societyos.utility.reference.domain.LocationRef;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRefRepository extends JpaRepository<LocationRef, UUID> {}
