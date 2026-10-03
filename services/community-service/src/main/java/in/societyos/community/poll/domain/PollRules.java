package in.societyos.community.poll.domain;

import in.societyos.community.platform.core.error.ProblemException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Voting rules, free of persistence so they are unit tested directly. */
public final class PollRules {

  public static final String PER_FLAT = "FLAT";
  public static final String PER_MEMBER = "MEMBER";

  /** One result line of a poll. */
  public record Result(UUID optionId, String label, long votes) {}

  private PollRules() {}

  /** The uniqueness key of a ballot: the flat (one vote per flat) or the voter (one per resident). */
  public static UUID ballotKey(String oneVotePer, UUID flatId, UUID userId) {
    return PER_FLAT.equals(oneVotePer) ? flatId : userId;
  }

  public static void requireOpen(String status, Instant opensAt, Instant closesAt, Instant now) {
    if (!"OPEN".equals(status) || (closesAt != null && !closesAt.isAfter(now))) {
      throw ProblemException.unprocessable("POLL_CLOSED", "Voting has closed");
    }
    if (opensAt.isAfter(now)) {
      throw ProblemException.unprocessable("POLL_NOT_OPEN", "Voting has not started yet");
    }
  }

  /** Validates a ballot's choices; returns them de-duplicated in the order given. */
  public static List<UUID> choices(boolean multiChoice, int maxChoices, Set<UUID> validOptions, Collection<UUID> chosen) {
    if (chosen == null || chosen.isEmpty()) {
      throw ProblemException.badRequest("NO_CHOICE", "Choose at least one option");
    }
    List<UUID> unique = List.copyOf(new LinkedHashSet<>(chosen));
    if (unique.size() != chosen.size()) {
      throw ProblemException.badRequest("DUPLICATE_CHOICE", "An option may be chosen only once");
    }
    if (!validOptions.containsAll(unique)) {
      throw ProblemException.badRequest("UNKNOWN_OPTION", "An option does not belong to this poll");
    }
    int limit = multiChoice ? maxChoices : 1;
    if (unique.size() > limit) {
      throw ProblemException.badRequest("TOO_MANY_CHOICES",
          multiChoice ? "Choose at most " + limit + " options" : "Choose exactly one option");
    }
    return unique;
  }

  /** Validates a poll definition. */
  public static void validateDefinition(String oneVotePer, boolean multiChoice, int maxChoices, int optionCount,
      Instant opensAt, Instant closesAt) {
    if (!PER_FLAT.equals(oneVotePer) && !PER_MEMBER.equals(oneVotePer)) {
      throw ProblemException.badRequest("INVALID_VOTE_SCOPE", "oneVotePer must be FLAT or MEMBER");
    }
    if (optionCount < 2 || optionCount > 20) {
      throw ProblemException.badRequest("INVALID_OPTIONS", "A poll needs 2 to 20 options");
    }
    if (multiChoice && (maxChoices < 1 || maxChoices > optionCount)) {
      throw ProblemException.badRequest("INVALID_MAX_CHOICES", "maxChoices must be between 1 and the option count");
    }
    if (closesAt != null && !closesAt.isAfter(opensAt)) {
      throw ProblemException.badRequest("INVALID_CLOSE_TIME", "closesAt must be after opensAt");
    }
  }

  /** Counts votes per option, keeping the option order. */
  public static List<Result> tally(Map<UUID, String> optionsInOrder, Collection<List<UUID>> ballots) {
    Map<UUID, Long> counts = new LinkedHashMap<>();
    optionsInOrder.keySet().forEach(id -> counts.put(id, 0L));
    for (List<UUID> ballot : ballots) {
      for (UUID option : ballot) {
        counts.computeIfPresent(option, (k, v) -> v + 1);
      }
    }
    return optionsInOrder.entrySet().stream()
        .map(e -> new Result(e.getKey(), e.getValue(), counts.get(e.getKey())))
        .toList();
  }
}
