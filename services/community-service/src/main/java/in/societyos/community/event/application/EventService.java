package in.societyos.community.event.application;

import in.societyos.community.directory.application.DirectoryService;
import in.societyos.community.directory.application.ResidentAccess;
import in.societyos.community.event.domain.CommunityEvent;
import in.societyos.community.event.domain.EventCreated;
import in.societyos.community.event.domain.EventRegistration;
import in.societyos.community.event.infrastructure.CommunityEventRepository;
import in.societyos.community.event.infrastructure.EventRegistrationRepository;
import in.societyos.community.notification.application.CommunityNotifier;
import in.societyos.community.notification.domain.NotificationRequested;
import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.core.tenant.TenantContext;
import in.societyos.community.platform.events.DomainEvents;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Community events and RSVPs (headcount up to the event capacity). */
@Service
public class EventService {

  public record EventView(CommunityEvent event, long goingHeadcount, EventRegistration mine) {}

  private final CommunityEventRepository events;
  private final EventRegistrationRepository registrations;
  private final ResidentAccess access;
  private final DirectoryService directory;
  private final CommunityNotifier notifier;
  private final DomainEvents domainEvents;
  private final Clock clock;

  public EventService(CommunityEventRepository events, EventRegistrationRepository registrations,
      ResidentAccess access, DirectoryService directory, CommunityNotifier notifier, DomainEvents domainEvents,
      Clock clock) {
    this.events = events;
    this.registrations = registrations;
    this.access = access;
    this.directory = directory;
    this.notifier = notifier;
    this.domainEvents = domainEvents;
    this.clock = clock;
  }

  @Transactional
  public EventView create(CommunityEvent.Details d) {
    if (!d.startsAt().isAfter(clock.instant())) {
      throw ProblemException.badRequest("EVENT_IN_PAST", "An event must start in the future");
    }
    CommunityEvent e = events.save(new CommunityEvent(d));
    domainEvents.publish(new EventCreated(e.getId(), e.getKind(), e.getTitle(), e.getStartsAt(), e.getEndsAt(),
        e.getFeePaise()));
    notifier.notify(directory.allResidents(), List.of(), NotificationRequested.NOTICE, "community.event.created",
        Map.of("title", e.getTitle(), "startsAt", e.getStartsAt().toString(), "eventId", e.getId().toString()),
        "community.event:" + e.getId());
    return new EventView(e, 0, null);
  }

  @Transactional(readOnly = true)
  public List<EventView> upcoming() {
    UUID me = TenantContext.userId().orElse(null);
    return events.findTop200BySocietyIdAndEndsAtAfterOrderByStartsAtAsc(TenantContext.activeSocietyId(), clock.instant())
        .stream().map(e -> view(e, me)).toList();
  }

  @Transactional(readOnly = true)
  public EventView get(UUID id) {
    return view(load(id), TenantContext.userId().orElse(null));
  }

  @Transactional
  public EventView rsvp(UUID eventId, UUID flatId, boolean going, int headcount) {
    access.requireOwnFlat(flatId);
    UUID me = access.currentUser();
    CommunityEvent e = events.lockById(eventId, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("event", eventId));
    e.requireOpenForRsvp(clock.instant());
    if (headcount < 1 || headcount > 20) {
      throw ProblemException.badRequest("INVALID_HEADCOUNT", "headcount must be between 1 and 20");
    }
    EventRegistration r = registrations.findByEventIdAndUserId(eventId, me)
        .orElseGet(() -> new EventRegistration(eventId, flatId, me));
    boolean existing = r.getVersion() != null;
    long othersGoing = registrations.goingHeadcount(eventId) - (existing && r.isGoing() ? r.getHeadcount() : 0);
    if (going && e.getCapacity() != null && othersGoing + headcount > e.getCapacity()) {
      throw ProblemException.conflict("EVENT_FULL", "Only " + Math.max(0, e.getCapacity() - othersGoing)
          + " place(s) left");
    }
    r.respond(flatId, going, headcount);
    registrations.save(r);
    return view(e, me);
  }

  @Transactional(readOnly = true)
  public List<EventRegistration> registrations(UUID eventId) {
    load(eventId);
    return registrations.findByEventIdOrderByCreatedAtAsc(eventId);
  }

  @Transactional
  public EventView cancel(UUID id) {
    CommunityEvent e = load(id);
    e.cancel();
    return view(e, null);
  }

  private CommunityEvent load(UUID id) {
    return events.findByIdAndSocietyId(id, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("event", id));
  }

  private EventView view(CommunityEvent e, UUID me) {
    EventRegistration mine = me == null ? null : registrations.findByEventIdAndUserId(e.getId(), me).orElse(null);
    return new EventView(e, registrations.goingHeadcount(e.getId()), mine);
  }
}
