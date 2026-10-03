package in.societyos.community.poll.domain;

import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** A poll: single or multi choice, one vote per flat or per resident; OPEN until closed. */
@Entity
@Table(name = "poll")
public class Poll extends TenantEntity {

  public record Details(String question, String oneVotePer, boolean multiChoice, int maxChoices, Instant opensAt,
      Instant closesAt) {}

  @Column(nullable = false) private String question;
  @Column(name = "one_vote_per", nullable = false) private String oneVotePer;
  @Column(name = "multi_choice", nullable = false) private boolean multiChoice;
  @Column(name = "max_choices", nullable = false) private int maxChoices;
  @Column(name = "opens_at", nullable = false) private Instant opensAt;
  @Column(name = "closes_at") private Instant closesAt;
  @Column(nullable = false) private String status;
  @Column(name = "closed_at") private Instant closedAt;

  protected Poll() {}

  public Poll(Details d) {
    this.question = d.question();
    this.oneVotePer = d.oneVotePer();
    this.multiChoice = d.multiChoice();
    this.maxChoices = d.multiChoice() ? d.maxChoices() : 1;
    this.opensAt = d.opensAt();
    this.closesAt = d.closesAt();
    this.status = "OPEN";
  }

  public void close(Instant now) {
    if (!isOpen()) {
      throw ProblemException.unprocessable("POLL_CLOSED", "The poll is already closed");
    }
    status = "CLOSED";
    closedAt = now;
  }

  public boolean isOpen() { return "OPEN".equals(status); }
  public boolean isDue(Instant now) { return isOpen() && closesAt != null && !closesAt.isAfter(now); }
  public String getQuestion() { return question; }
  public String getOneVotePer() { return oneVotePer; }
  public boolean isMultiChoice() { return multiChoice; }
  public int getMaxChoices() { return maxChoices; }
  public Instant getOpensAt() { return opensAt; }
  public Instant getClosesAt() { return closesAt; }
  public String getStatus() { return status; }
  public Instant getClosedAt() { return closedAt; }
}
