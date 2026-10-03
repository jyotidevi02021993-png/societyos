package in.societyos.community.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.community.notice.domain.Audience;
import in.societyos.community.platform.core.UuidV7;
import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.poll.domain.PollRules;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PollRulesTest {

  final UUID a = UuidV7.next();
  final UUID b = UuidV7.next();
  final UUID c = UuidV7.next();
  final Set<UUID> options = Set.of(a, b, c);

  static String code(Runnable r) {
    try {
      r.run();
    } catch (ProblemException e) {
      return e.code();
    }
    return "OK";
  }

  @Test
  void ballotKeyIsTheFlatOrTheResident() {
    UUID flat = UuidV7.next();
    UUID user = UuidV7.next();
    assertThat(PollRules.ballotKey("FLAT", flat, user)).isEqualTo(flat);
    assertThat(PollRules.ballotKey("MEMBER", flat, user)).isEqualTo(user);
  }

  @Test
  void singleAndMultiChoice() {
    assertThat(PollRules.choices(false, 1, options, List.of(a))).containsExactly(a);
    assertThat(code(() -> PollRules.choices(false, 1, options, List.of(a, b)))).isEqualTo("TOO_MANY_CHOICES");
    assertThat(PollRules.choices(true, 2, options, List.of(c, a))).containsExactly(c, a);
    assertThat(code(() -> PollRules.choices(true, 2, options, List.of(a, b, c)))).isEqualTo("TOO_MANY_CHOICES");
    assertThat(code(() -> PollRules.choices(true, 3, options, List.of(a, a)))).isEqualTo("DUPLICATE_CHOICE");
    assertThat(code(() -> PollRules.choices(true, 3, options, List.of(UuidV7.next())))).isEqualTo("UNKNOWN_OPTION");
    assertThat(code(() -> PollRules.choices(false, 1, options, List.of()))).isEqualTo("NO_CHOICE");
  }

  @Test
  void openWindow() {
    Instant now = Instant.parse("2026-10-05T10:00:00Z");
    PollRules.requireOpen("OPEN", now.minusSeconds(60), now.plusSeconds(60), now);
    PollRules.requireOpen("OPEN", now.minusSeconds(60), null, now);
    assertThat(code(() -> PollRules.requireOpen("CLOSED", now.minusSeconds(60), null, now))).isEqualTo("POLL_CLOSED");
    assertThat(code(() -> PollRules.requireOpen("OPEN", now.minusSeconds(60), now, now))).isEqualTo("POLL_CLOSED");
    assertThat(code(() -> PollRules.requireOpen("OPEN", now.plusSeconds(60), null, now))).isEqualTo("POLL_NOT_OPEN");
  }

  @Test
  void definitionAndTally() {
    Instant t = Instant.parse("2026-10-05T10:00:00Z");
    assertThat(code(() -> PollRules.validateDefinition("HOUSE", false, 1, 2, t, null))).isEqualTo("INVALID_VOTE_SCOPE");
    assertThat(code(() -> PollRules.validateDefinition("FLAT", false, 1, 1, t, null))).isEqualTo("INVALID_OPTIONS");
    assertThat(code(() -> PollRules.validateDefinition("FLAT", true, 4, 3, t, null))).isEqualTo("INVALID_MAX_CHOICES");
    assertThat(code(() -> PollRules.validateDefinition("FLAT", false, 1, 2, t, t))).isEqualTo("INVALID_CLOSE_TIME");

    Map<UUID, String> ordered = new LinkedHashMap<>();
    ordered.put(a, "Yes");
    ordered.put(b, "No");
    ordered.put(c, "Abstain");
    List<PollRules.Result> r = PollRules.tally(ordered, List.of(List.of(a), List.of(a, c), List.of(b)));
    assertThat(r).extracting(PollRules.Result::votes).containsExactly(2L, 1L, 1L);
    assertThat(r.getFirst().label()).isEqualTo("Yes");
  }

  @Test
  void noticeAudienceMatching() {
    UUID towerA = UuidV7.next();
    Audience towers = new Audience(false, List.of(towerA), List.of());
    assertThat(towers.matches(Set.of(towerA), Set.of())).isTrue();
    assertThat(towers.matches(Set.of(UuidV7.next()), Set.of("RESIDENT_OWNER"))).isFalse();
    Audience roles = new Audience(false, List.of(), List.of("rwa_committee"));
    assertThat(roles.matches(Set.of(), Set.of("RWA_COMMITTEE"))).isTrue();
    assertThat(Audience.everyone().matches(Set.of(), Set.of())).isTrue();
    assertThatThrownBy(() -> new Audience(false, List.of(), List.of()).validated()).isInstanceOf(ProblemException.class);
  }
}
