package in.societyos.community.poll.infrastructure;

import in.societyos.community.poll.domain.PollOption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PollOptionRepository extends JpaRepository<PollOption, UUID> {

  List<PollOption> findByPollIdOrderByPositionAsc(UUID pollId);
}
