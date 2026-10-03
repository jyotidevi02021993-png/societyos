package in.societyos.workflow.sla.application;

import in.societyos.workflow.platform.core.tenant.TenantContext;
import in.societyos.workflow.platform.events.DomainEvents;
import in.societyos.workflow.scheduling.application.Timers;
import in.societyos.workflow.sla.domain.EscalationStep;
import in.societyos.workflow.sla.domain.SlaEvents;
import in.societyos.workflow.sla.domain.SlaPolicy;
import in.societyos.workflow.sla.domain.SlaTimer;
import in.societyos.workflow.sla.infrastructure.SlaPolicyRepository;
import in.societyos.workflow.sla.infrastructure.SlaTimerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * SLA timers: started when a ticket is raised (policy: subject type × category × priority),
 * stopped when it is responded to / resolved, fired by db-scheduler: warning, breach, then each
 * escalation step of the chain.
 */
@Service
public class SlaService implements Timers.TimerHandler {

  public static final String TASK = "sla-timer";
  private static final Logger log = LoggerFactory.getLogger(SlaService.class);
  private static final TypeReference<List<EscalationStep>> CHAIN = new TypeReference<>() {};

  private final SlaTimerRepository timers;
  private final SlaPolicyRepository policies;
  private final Timers wakeups;
  private final DomainEvents events;
  private final Escalations escalations;
  private final JsonMapper json;
  private final Clock clock;

  public SlaService(SlaTimerRepository timers, SlaPolicyRepository policies, Timers wakeups, DomainEvents events,
      Escalations escalations, JsonMapper json, Clock clock) {
    this.timers = timers;
    this.policies = policies;
    this.wakeups = wakeups;
    this.events = events;
    this.escalations = escalations;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public String taskName() {
    return TASK;
  }

  /** Starts the RESPOND (if the policy has one) and RESOLVE timers of a new subject. */
  @Transactional
  public List<SlaTimer> start(String subjectType, UUID subjectId, String subjectRef, String category,
      String priority, Instant startedAt) {
    Optional<SlaPolicy> policy = policyFor(subjectType, category, priority);
    if (policy.isEmpty()) {
      log.info("No SLA policy for {} {} (category {}, priority {}): no timer", subjectType, subjectId, category, priority);
      return List.of();
    }
    SlaPolicy p = policy.get();
    Instant start = startedAt == null ? clock.instant() : startedAt;
    List<SlaTimer> started = new java.util.ArrayList<>();
    if (p.getRespondMins() != null) {
      startTimer(subjectType, subjectId, subjectRef, SlaTimer.Kind.RESPOND, p, start).ifPresent(started::add);
    }
    startTimer(subjectType, subjectId, subjectRef, SlaTimer.Kind.RESOLVE, p, start).ifPresent(started::add);
    return started;
  }

  /** A reopened subject gets a fresh RESOLVE clock under the policy of its previous one. */
  @Transactional
  public void restart(String subjectType, UUID subjectId, Instant at) {
    timers.findBySubjectTypeAndSubjectIdOrderByStartedAtAsc(subjectType, subjectId).stream()
        .filter(t -> t.getKind() == SlaTimer.Kind.RESOLVE && t.getPolicyId() != null).reduce((a, b) -> b)
        .ifPresent(last -> {
          stop(subjectType, subjectId, null, at);
          policies.findById(last.getPolicyId()).ifPresent(p -> startTimer(subjectType, subjectId,
              last.getSubjectRef(), SlaTimer.Kind.RESOLVE, p, at == null ? clock.instant() : at));
        });
  }

  /** Stops the subject's live timers of {@code kind} (all kinds when null). */
  @Transactional
  public int stop(String subjectType, UUID subjectId, SlaTimer.Kind kind, Instant at) {
    Instant now = at == null ? clock.instant() : at;
    int stopped = 0;
    for (SlaTimer t : timers.findBySubjectTypeAndSubjectIdOrderByStartedAtAsc(subjectType, subjectId)) {
      if ((kind == null || t.getKind() == kind) && t.stop(now)) {
        timers.saveAndFlush(t);
        stopped++;
      }
    }
    return stopped;
  }

  @Override
  @Transactional
  public void wake(UUID timerId) {
    Optional<SlaTimer> found = timers.findById(timerId);
    if (found.isEmpty()) {
      return;
    }
    SlaTimer timer = found.get();
    Instant now = clock.instant();
    SlaTimer.Tick tick = timer.tick(now, chain(timer));
    if (tick.warn()) {
      events.publish(SlaEvents.warning(timer));
    }
    if (tick.breach()) {
      events.publish(SlaEvents.breached(timer));
    }
    for (SlaTimer.Escalation e : tick.escalations()) {
      escalations.escalate(timer.getSubjectType(), timer.getSubjectId(), timer.getSubjectRef(), e.level(), e.toRole(),
          Escalations.SLA, timer.getId(), now);
    }
    timers.save(timer);
    Instant next = timer.nextWakeAt();
    if (next != null) {
      wakeups.wakeAt(TASK, TenantContext.activeSocietyId(), timer.getId(), next.isAfter(now) ? next : now);
    }
  }

  @Transactional(readOnly = true)
  public List<SlaTimer> timersOf(String subjectType, UUID subjectId) {
    return timers.findBySubjectTypeAndSubjectIdOrderByStartedAtAsc(subjectType, subjectId);
  }

  @Transactional(readOnly = true)
  public List<SlaTimer> active() {
    return timers.findByStatusOrderByDueAtAsc(SlaTimer.Status.ACTIVE);
  }

  /** Most specific active policy: category + priority, then category, then priority, then catch-all. */
  @Transactional(readOnly = true)
  public Optional<SlaPolicy> policyFor(String subjectType, String category, String priority) {
    return policies.findBySubjectTypeAndActiveTrue(subjectType).stream()
        .filter(p -> p.matches(category, priority))
        .max(Comparator.comparingInt(SlaPolicy::specificity));
  }

  private Optional<SlaTimer> startTimer(String subjectType, UUID subjectId, String subjectRef, SlaTimer.Kind kind,
      SlaPolicy p, Instant start) {
    boolean live = timers.findBySubjectTypeAndSubjectIdOrderByStartedAtAsc(subjectType, subjectId).stream()
        .anyMatch(t -> t.getKind() == kind && t.getStatus() != SlaTimer.Status.STOPPED);
    if (live) {
      return Optional.empty();
    }
    int minutes = kind == SlaTimer.Kind.RESPOND ? p.getRespondMins() : p.getResolveMins();
    SlaTimer timer = timers.save(new SlaTimer(subjectType, subjectId, subjectRef, kind, p.getId(), start, minutes,
        p.getWarnPercent(), p.getEscalationChainJson()));
    wakeups.wakeAt(TASK, TenantContext.activeSocietyId(), timer.getId(), timer.nextWakeAt());
    return Optional.of(timer);
  }

  private List<EscalationStep> chain(SlaTimer timer) {
    return json.readValue(timer.getEscalationChainJson(), CHAIN);
  }
}
