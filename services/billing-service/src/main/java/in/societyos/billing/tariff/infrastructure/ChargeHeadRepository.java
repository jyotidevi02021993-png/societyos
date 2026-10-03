package in.societyos.billing.tariff.infrastructure;

import in.societyos.billing.tariff.domain.ChargeHead;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargeHeadRepository extends JpaRepository<ChargeHead, UUID> {

  List<ChargeHead> findAllByOrderBySortOrderAscCodeAsc();

  List<ChargeHead> findByActiveTrueOrderBySortOrderAscCodeAsc();

  boolean existsByCodeIgnoreCase(String code);
}
