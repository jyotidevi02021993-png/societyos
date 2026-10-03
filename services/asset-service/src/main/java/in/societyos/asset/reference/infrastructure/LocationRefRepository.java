package in.societyos.asset.reference.infrastructure;

import in.societyos.asset.reference.domain.LocationRef;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRefRepository extends JpaRepository<LocationRef, UUID> {}
