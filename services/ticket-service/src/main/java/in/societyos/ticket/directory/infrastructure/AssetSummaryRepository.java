package in.societyos.ticket.directory.infrastructure;

import in.societyos.ticket.directory.domain.AssetSummary;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetSummaryRepository extends JpaRepository<AssetSummary, UUID> {}
