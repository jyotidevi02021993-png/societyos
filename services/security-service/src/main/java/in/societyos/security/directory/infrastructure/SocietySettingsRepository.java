package in.societyos.security.directory.infrastructure;

import in.societyos.security.directory.domain.SocietySettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocietySettingsRepository extends JpaRepository<SocietySettings, UUID> {}
