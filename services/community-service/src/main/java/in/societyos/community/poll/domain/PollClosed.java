package in.societyos.community.poll.domain;

import in.societyos.community.common.CommunityDomainEvent;
import java.util.List;
import java.util.UUID;

/** {@code community.poll.closed} (catalogue: pollId, question, results [{optionId, label, votes}]). */
public record PollClosed(UUID pollId, String question, List<PollRules.Result> results) implements CommunityDomainEvent {
  @Override public String type() { return "community.poll.closed"; }
  @Override public UUID aggregateId() { return pollId; }
}
