package in.societyos.media.media.infrastructure;

import in.societyos.media.media.domain.MediaSocietySettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaSocietySettingsRepository extends JpaRepository<MediaSocietySettings, UUID> {

  /** RLS leaves at most the bound society's row. */
  Optional<MediaSocietySettings> findFirstBy();
}
