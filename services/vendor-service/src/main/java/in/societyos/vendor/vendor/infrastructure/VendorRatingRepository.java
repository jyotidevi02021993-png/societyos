package in.societyos.vendor.vendor.infrastructure;

import in.societyos.vendor.vendor.domain.VendorRating;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorRatingRepository extends JpaRepository<VendorRating, UUID> {}
