package in.societyos.society.member.infrastructure;

import in.societyos.society.member.domain.FlatMembership;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlatMembershipRepository extends JpaRepository<FlatMembership, UUID> {
  List<FlatMembership> findByFlatIdOrderByFromDateAsc(UUID flatId);

  List<FlatMembership> findByFlatIdAndEndedAtIsNullOrderByFromDateAsc(UUID flatId);

  List<FlatMembership> findAllByOrderByFromDateAsc();

  List<FlatMembership> findByEndedAtIsNullOrderByFromDateAsc();

  List<FlatMembership> findByUserIdAndEndedAtIsNull(UUID userId);

  List<FlatMembership> findByResidentIdInAndEndedAtIsNull(Collection<UUID> residentIds);

  boolean existsByFlatIdAndResidentIdAndEndedAtIsNull(UUID flatId, UUID residentId);

  boolean existsByFlatIdAndKindAndPrimaryTrueAndEndedAtIsNull(UUID flatId, String kind);

  boolean existsByFlatIdAndKindInAndEndedAtIsNull(UUID flatId, Collection<String> kinds);

  boolean existsByFlatIdAndUserIdAndKindInAndEndedAtIsNull(UUID flatId, UUID userId, Collection<String> kinds);
}
