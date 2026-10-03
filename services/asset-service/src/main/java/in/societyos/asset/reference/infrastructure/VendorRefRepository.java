package in.societyos.asset.reference.infrastructure;

import in.societyos.asset.reference.domain.VendorRef;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorRefRepository extends JpaRepository<VendorRef, UUID> {}
