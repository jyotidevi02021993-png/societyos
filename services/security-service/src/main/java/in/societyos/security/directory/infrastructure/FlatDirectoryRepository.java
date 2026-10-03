package in.societyos.security.directory.infrastructure;

import in.societyos.security.directory.domain.FlatDirectoryEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatDirectoryRepository extends JpaRepository<FlatDirectoryEntry, UUID> {

  List<FlatDirectoryEntry> findBySocietyIdAndLabelIgnoreCase(UUID societyId, String label);

  List<FlatDirectoryEntry> findByIdIn(java.util.Collection<UUID> ids);

  List<FlatDirectoryEntry> findTop20BySocietyIdAndLabelContainingIgnoreCaseOrderByLabelAsc(UUID societyId, String label);
}
