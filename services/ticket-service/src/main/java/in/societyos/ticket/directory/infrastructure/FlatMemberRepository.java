package in.societyos.ticket.directory.infrastructure;

import in.societyos.ticket.directory.domain.FlatMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatMemberRepository extends JpaRepository<FlatMember, UUID> {
  List<FlatMember> findByUserIdAndActiveTrue(UUID userId);
  List<FlatMember> findByFlatIdAndActiveTrue(UUID flatId);
}
