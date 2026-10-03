package in.societyos.community.poll.api;

import in.societyos.community.poll.application.PollService;
import in.societyos.community.poll.application.PollService.PollView;
import in.societyos.community.poll.domain.Poll;
import in.societyos.community.poll.domain.PollRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/polls")
class PollController {

  private final PollService polls;

  PollController(PollService polls) {
    this.polls = polls;
  }

  record PollRequest(@NotBlank @Size(max = 300) String question, @NotEmpty List<@NotBlank String> options,
      String oneVotePer, Boolean multiChoice, Integer maxChoices, Instant opensAt, Instant closesAt) {}

  record VoteRequest(@NotNull UUID flatId, @NotEmpty List<UUID> optionIds) {}

  record OptionDto(UUID id, String label) {}

  record PollResponse(UUID id, String question, String oneVotePer, boolean multiChoice, int maxChoices,
      Instant opensAt, Instant closesAt, String status, Instant closedAt, List<OptionDto> options,
      List<PollRules.Result> results, long ballots, List<UUID> myChoices) {
    static PollResponse from(PollView v) {
      Poll p = v.poll();
      return new PollResponse(p.getId(), p.getQuestion(), p.getOneVotePer(), p.isMultiChoice(), p.getMaxChoices(),
          p.getOpensAt(), p.getClosesAt(), p.getStatus(), p.getClosedAt(),
          v.options().stream().map(o -> new OptionDto(o.getId(), o.getLabel())).toList(),
          v.results(), v.ballots(), v.myChoices());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('poll:vote', 'poll:create')")
  List<PollResponse> list() {
    return polls.list().stream().map(PollResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('poll:create')")
  ResponseEntity<PollResponse> create(@Valid @RequestBody PollRequest r) {
    boolean multi = Boolean.TRUE.equals(r.multiChoice());
    int max = r.maxChoices() == null ? (multi ? r.options().size() : 1) : r.maxChoices();
    PollView v = polls.create(new Poll.Details(r.question(), r.oneVotePer(), multi, max, r.opensAt(),
        r.closesAt()), r.options());
    return ResponseEntity.created(URI.create("/v1/polls/" + v.poll().getId())).body(PollResponse.from(v));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('poll:vote', 'poll:create')")
  PollResponse get(@PathVariable UUID id) {
    return PollResponse.from(polls.get(id));
  }

  @PostMapping("/{id}/vote")
  @PreAuthorize("@perm.has('poll:vote')")
  PollResponse vote(@PathVariable UUID id, @Valid @RequestBody VoteRequest r) {
    return PollResponse.from(polls.vote(id, r.flatId(), r.optionIds()));
  }

  @PostMapping("/{id}/close")
  @PreAuthorize("@perm.has('poll:create')")
  PollResponse close(@PathVariable UUID id) {
    return PollResponse.from(polls.close(id));
  }
}
