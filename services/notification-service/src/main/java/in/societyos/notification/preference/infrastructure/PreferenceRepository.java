package in.societyos.notification.preference.infrastructure;

import in.societyos.notification.preference.domain.Preference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceRepository extends JpaRepository<Preference, UUID> {

  Optional<Preference> findBySocietyIdAndUserId(UUID societyId, UUID userId);
}
