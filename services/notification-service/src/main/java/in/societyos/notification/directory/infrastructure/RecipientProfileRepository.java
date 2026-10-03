package in.societyos.notification.directory.infrastructure;

import in.societyos.notification.directory.domain.RecipientProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipientProfileRepository extends JpaRepository<RecipientProfile, UUID> {

  Optional<RecipientProfile> findByUserId(UUID userId);
}
