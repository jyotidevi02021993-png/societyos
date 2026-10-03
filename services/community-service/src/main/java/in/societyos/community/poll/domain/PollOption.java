package in.societyos.community.poll.domain;

import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "poll_option")
public class PollOption extends TenantEntity {

  @Column(name = "poll_id", nullable = false) private UUID pollId;
  @Column(nullable = false) private String label;
  @Column(nullable = false) private int position;

  protected PollOption() {}

  public PollOption(UUID pollId, String label, int position) {
    this.pollId = pollId;
    this.label = label;
    this.position = position;
  }

  public UUID getPollId() { return pollId; }
  public String getLabel() { return label; }
  public int getPosition() { return position; }
}
