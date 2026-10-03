package in.societyos.billing.roster.infrastructure;

import in.societyos.billing.roster.domain.BillingSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingSettingsRepository extends JpaRepository<BillingSettings, UUID> {}
