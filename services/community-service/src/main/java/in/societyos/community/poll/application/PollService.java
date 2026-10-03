package in.societyos.community.poll.application;

import in.societyos.community.directory.application.DirectoryService;
import in.societyos.community.directory.application.ResidentAccess;
import in.societyos.community.notification.application.CommunityNotifier;
import in.societyos.community.notification.domain.NotificationRequested;
import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.core.tenant.TenantContext;
import in.societyos.community.platform.events.DomainEvents;
import in.societyos.community.poll.domain.Poll;
import in.societyos.community.poll.domain.PollBallot;
import in.societyos.community.poll.domain.PollClosed;
import in.societyos.community.poll.domain.PollOption;
import in.societyos.community.poll.domain.PollRules;
import in.societyos.community.poll.infrastructure.PollBallotRepository;
import in.societyos.community.poll.infrastructure.PollOptionRepository;
import in.societyos.community.poll.infrastructure.PollRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Polls: create, vote (one per flat or per resident), close with results. */
@Service
public class PollService {

  public static final String CREATE = "poll:create";

  /**
   * A poll as the caller sees it. {@code results} are shown once closed, or to poll creators;
   * {@code myChoices} are the caller's (or their flat's) choices, if any.
   */
  public record PollView(Poll poll, List<PollOption> options, List<PollRules.Result> results, long ballots,
      List<UUID> myChoices) {}

  private final PollRepository polls;
  private final PollOptionRepository options;
  private final PollBallotRepository ballots;
  private final ResidentAccess access;
  private final DirectoryService directory;
  private final CommunityNotifier notifier;
  private final DomainEvents events;
  private final JsonMapper json;
  private final Clock clock;

  public PollService(PollRepository polls, PollOptionRepository options, PollBallotRepository ballots,
      ResidentAccess access, DirectoryService directory, CommunityNotifier notifier, DomainEvents events,
      JsonMapper json, Clock clock) {
    this.polls = polls;
    this.options = options;
    this.ballots = ballots;
    this.access = access;
    this.directory = directory;
    this.notifier = notifier;
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public PollView create(Poll.Details d, List<String> optionLabels) {
    Instant opensAt = d.opensAt() == null ? clock.instant() : d.opensAt();
    List<String> labels = optionLabels == null ? List.of()
        : optionLabels.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    String scope = d.oneVotePer() == null ? PollRules.PER_FLAT : d.oneVotePer().trim().toUpperCase();
    PollRules.validateDefinition(scope, d.multiChoice(), d.maxChoices(), labels.size(), opensAt, d.closesAt());
    Poll poll = polls.save(new Poll(new Poll.Details(d.question().trim(), scope, d.multiChoice(), d.maxChoices(),
        opensAt, d.closesAt())));
    List<PollOption> saved = new ArrayList<>();
    for (int i = 0; i < labels.size(); i++) {
      saved.add(options.save(new PollOption(poll.getId(), labels.get(i), i)));
    }
    notifier.notify(directory.allResidents(), List.of(), NotificationRequested.NOTICE, "community.poll.created",
        Map.of("question", poll.getQuestion(), "pollId", poll.getId().toString()), "community.poll:" + poll.getId());
    return view(poll, saved);
  }

  @Transactional(readOnly = true)
  public List<PollView> list() {
    return polls.findTop100BySocietyIdOrderByCreatedAtDesc(TenantContext.activeSocietyId()).stream()
        .map(p -> view(p, options.findByPollIdOrderByPositionAsc(p.getId()))).toList();
  }

  @Transactional(readOnly = true)
  public PollView get(UUID id) {
    Poll p = load(id);
    return view(p, options.findByPollIdOrderByPositionAsc(id));
  }

  @Transactional
  public PollView vote(UUID pollId, UUID flatId, List<UUID> optionIds) {
    access.requireOwnFlat(flatId);
    UUID me = access.currentUser();
    Poll poll = polls.lockById(pollId, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("poll", pollId));
    PollRules.requireOpen(poll.getStatus(), poll.getOpensAt(), poll.getClosesAt(), clock.instant());
    List<PollOption> opts = options.findByPollIdOrderByPositionAsc(pollId);
    Set<UUID> valid = opts.stream().map(PollOption::getId).collect(Collectors.toSet());
    List<UUID> chosen = PollRules.choices(poll.isMultiChoice(), poll.getMaxChoices(), valid, optionIds);
    UUID key = PollRules.ballotKey(poll.getOneVotePer(), flatId, me);
    if (ballots.existsByPollIdAndBallotKey(pollId, key)) {
      throw ProblemException.conflict("ALREADY_VOTED", PollRules.PER_FLAT.equals(poll.getOneVotePer())
          ? "This flat has already voted" : "You have already voted");
    }
    ballots.saveAndFlush(new PollBallot(pollId, key, flatId, me, json.writeValueAsString(chosen)));
    return view(poll, opts);
  }

  @Transactional
  public PollView close(UUID id) {
    Poll poll = polls.lockById(id, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("poll", id));
    poll.close(clock.instant());
    return announceClosed(poll);
  }

  /** Polls past {@code closesAt} (db-scheduler job, per society). */
  @Transactional
  public int closeDue() {
    Instant now = clock.instant();
    int n = 0;
    for (Poll p : polls.findBySocietyIdAndStatusAndClosesAtLessThanEqual(TenantContext.activeSocietyId(), "OPEN", now)) {
      p.close(now);
      announceClosed(p);
      n++;
    }
    return n;
  }

  private PollView announceClosed(Poll poll) {
    List<PollOption> opts = options.findByPollIdOrderByPositionAsc(poll.getId());
    List<PollRules.Result> results = tally(poll.getId(), opts);
    events.publish(new PollClosed(poll.getId(), poll.getQuestion(), results));
    return view(poll, opts);
  }

  private List<PollRules.Result> tally(UUID pollId, List<PollOption> opts) {
    Map<UUID, String> ordered = new LinkedHashMap<>();
    opts.forEach(o -> ordered.put(o.getId(), o.getLabel()));
    return PollRules.tally(ordered, ballots.findByPollId(pollId).stream().map(this::choices).toList());
  }

  private List<UUID> choices(PollBallot b) {
    return json.readValue(b.getOptionIdsJson(), new TypeReference<List<UUID>>() {});
  }

  private Poll load(UUID id) {
    return polls.findByIdAndSocietyId(id, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("poll", id));
  }

  private PollView view(Poll poll, List<PollOption> opts) {
    boolean showResults = !poll.isOpen() || access.has(CREATE);
    List<PollBallot> cast = ballots.findByPollId(poll.getId());
    UUID me = TenantContext.userId().orElse(null);
    List<UUID> mine = List.of();
    if (me != null) {
      Set<UUID> myFlats = PollRules.PER_FLAT.equals(poll.getOneVotePer()) ? directory.flatsOf(me) : Set.of();
      mine = cast.stream()
          .filter(b -> PollRules.PER_FLAT.equals(poll.getOneVotePer()) ? myFlats.contains(b.getFlatId())
              : me.equals(b.getUserId()))
          .findFirst().map(this::choices).orElse(List.of());
    }
    return new PollView(poll, opts, showResults ? tally(poll.getId(), opts) : null, cast.size(), mine);
  }
}
