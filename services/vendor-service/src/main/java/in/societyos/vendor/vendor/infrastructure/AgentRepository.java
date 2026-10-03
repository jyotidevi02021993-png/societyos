package in.societyos.vendor.vendor.infrastructure;

import in.societyos.vendor.vendor.domain.Agent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

  boolean existsByCode(String code);

  Optional<Agent> findByUserId(UUID userId);

  List<Agent> findByVendorIdOrderByNameAsc(UUID vendorId);

  List<Agent> findAllByOrderByNameAsc();
}
