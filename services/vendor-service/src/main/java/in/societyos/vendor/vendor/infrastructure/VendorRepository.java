package in.societyos.vendor.vendor.infrastructure;

import in.societyos.vendor.vendor.domain.Vendor;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

  boolean existsByCode(String code);

  List<Vendor> findAllByOrderByNameAsc();

  List<Vendor> findByCategoryOrderByNameAsc(String category);
}
