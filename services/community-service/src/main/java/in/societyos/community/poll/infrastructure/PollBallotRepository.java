package in.societyos.community.poll.infrastructure;

import in.societyos.community.poll.domain.PollBallot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PollBallotRepository extends JpaRepository<PollBallot, UUID> {

  List<PollBallot> findByPollId(UUID pollId);

  boolean existsByPollIdAndBallotKey(UUID pollId, UUID ballotKey);

  Optional<PollBallot> findFirstByPollIdAndUserId(UUID pollId, UUID userId);
}
