package in.societyos.audit.log.infrastructure;

import in.societyos.audit.log.domain.AuditExport;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditExportRepository extends JpaRepository<AuditExport, UUID> {}
