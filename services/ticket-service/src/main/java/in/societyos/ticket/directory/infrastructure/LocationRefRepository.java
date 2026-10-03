package in.societyos.ticket.directory.infrastructure;

import in.societyos.ticket.directory.domain.LocationRef;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRefRepository extends JpaRepository<LocationRef, UUID> {}
