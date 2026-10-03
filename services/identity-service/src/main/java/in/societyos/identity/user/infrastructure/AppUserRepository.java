package in.societyos.identity.user.infrastructure;

import in.societyos.identity.user.domain.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  Optional<AppUser> findByPhoneE164(String phoneE164);

  Optional<AppUser> findByEmailIgnoreCase(String email);

  boolean existsByPlatformRole(String platformRole);
}
