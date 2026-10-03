package in.societyos.ticket.directory.infrastructure;

import in.societyos.ticket.directory.domain.FlatRef;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatRefRepository extends JpaRepository<FlatRef, UUID> {}
