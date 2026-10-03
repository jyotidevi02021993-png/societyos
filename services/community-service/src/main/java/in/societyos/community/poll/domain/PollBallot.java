package in.societyos.community.poll.domain;

import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** One cast ballot; UNIQUE(poll_id, ballot_key) makes a second vote from the same flat/voter fail. */
@Entity
@Table(name = "poll_ballot")
public class PollBallot extends TenantEntity {

  @Column(name = "poll_id", nullable = false) private UUID pollId;
  @Column(name = "ballot_key", nullable = false) private UUID ballotKey;
  @Column(name = "flat_id", nullable = false) private UUID flatId;
  @Column(name = "user_id", nullable = false) private UUID userId;

  @Column(name = "option_ids", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String optionIdsJson;

  protected PollBallot() {}

  public PollBallot(UUID pollId, UUID ballotKey, UUID flatId, UUID userId, String optionIdsJson) {
    this.pollId = pollId;
    this.ballotKey = ballotKey;
    this.flatId = flatId;
    this.userId = userId;
    this.optionIdsJson = optionIdsJson;
  }

  public UUID getPollId() { return pollId; }
  public UUID getBallotKey() { return ballotKey; }
  public UUID getFlatId() { return flatId; }
  public UUID getUserId() { return userId; }
  public String getOptionIdsJson() { return optionIdsJson; }
}
