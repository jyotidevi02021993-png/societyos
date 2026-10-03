package in.societyos.billing.roster.infrastructure;

import in.societyos.billing.roster.domain.FlatMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatMemberRepository extends JpaRepository<FlatMember, UUID> {

  List<FlatMember> findByUserIdAndEndedAtIsNull(UUID userId);

  List<FlatMember> findByFlatIdAndEndedAtIsNull(UUID flatId);

  boolean existsByUserIdAndFlatIdAndEndedAtIsNull(UUID userId, UUID flatId);
}
