package in.societyos.ticket.directory.infrastructure;

import in.societyos.ticket.directory.domain.RoleHolder;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleHolderRepository extends JpaRepository<RoleHolder, UUID> {
  List<RoleHolder> findByRoleCode(String roleCode);
}
