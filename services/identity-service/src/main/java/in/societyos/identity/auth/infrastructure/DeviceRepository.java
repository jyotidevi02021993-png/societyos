package in.societyos.identity.auth.infrastructure;

import in.societyos.identity.auth.domain.Device;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

  List<Device> findByUserIdAndRevokedAtIsNull(UUID userId);
}
