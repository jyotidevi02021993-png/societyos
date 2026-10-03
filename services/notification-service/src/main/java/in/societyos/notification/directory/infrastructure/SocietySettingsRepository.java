package in.societyos.notification.directory.infrastructure;

import in.societyos.notification.directory.domain.SocietySettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocietySettingsRepository extends JpaRepository<SocietySettings, UUID> {}
