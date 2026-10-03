package in.societyos.notification.directory.infrastructure;

import in.societyos.notification.directory.domain.DeviceToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

  Optional<DeviceToken> findByDeviceId(UUID deviceId);

  List<DeviceToken> findByUserId(UUID userId);
}
