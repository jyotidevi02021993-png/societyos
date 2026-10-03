package in.societyos.society.imports.infrastructure;

import in.societyos.society.imports.domain.ImportJob;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
  List<ImportJob> findTop50ByOrderByCreatedAtDesc();
}
