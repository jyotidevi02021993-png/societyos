package in.societyos.community.event.api;

import in.societyos.community.event.application.EventService;
import in.societyos.community.event.application.EventService.EventView;
import in.societyos.community.event.domain.CommunityEvent;
import in.societyos.community.event.domain.EventRegistration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
class EventController {

  private final EventService events;

  EventController(EventService events) {
    this.events = events;
  }

  record EventRequest(@NotBlank String kind, @NotBlank @Size(max = 200) String title,
      @Size(max = 5000) String description, @Size(max = 200) String location, @NotNull Instant startsAt,
      @NotNull Instant endsAt, Integer capacity, @Min(0) Long feePaise) {}

  record RsvpRequest(@NotNull UUID flatId, Boolean going, Integer headcount) {}

  record MyRsvp(String status, int headcount, UUID flatId) {}

  record EventResponse(UUID id, String kind, String title, String description, String location, Instant startsAt,
      Instant endsAt, Integer capacity, long feePaise, String status, long goingHeadcount, MyRsvp myRsvp) {
    static EventResponse from(EventView v) {
      CommunityEvent e = v.event();
      EventRegistration r = v.mine();
      return new EventResponse(e.getId(), e.getKind(), e.getTitle(), e.getDescription(), e.getLocation(),
          e.getStartsAt(), e.getEndsAt(), e.getCapacity(), e.getFeePaise(), e.getStatus(), v.goingHeadcount(),
          r == null ? null : new MyRsvp(r.getStatus(), r.getHeadcount(), r.getFlatId()));
    }
  }

  record RegistrationResponse(UUID userId, UUID flatId, String status, int headcount, Instant at) {}

  @GetMapping
  @PreAuthorize("@perm.hasAny('notice:view', 'event:manage')")
  List<EventResponse> upcoming() {
    return events.upcoming().stream().map(EventResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('event:manage')")
  ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest r) {
    EventView v = events.create(new CommunityEvent.Details(r.kind().trim().toUpperCase(), r.title().trim(),
        r.description(), r.location(), r.startsAt(), r.endsAt(), r.capacity(), r.feePaise() == null ? 0 : r.feePaise()));
    return ResponseEntity.created(URI.create("/v1/events/" + v.event().getId())).body(EventResponse.from(v));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('notice:view', 'event:manage')")
  EventResponse get(@PathVariable UUID id) {
    return EventResponse.from(events.get(id));
  }

  /** RSVP for the caller (a resident of {@code flatId}); calling again changes the answer. */
  @PutMapping("/{id}/rsvp")
  @PreAuthorize("@perm.hasAny('notice:view', 'event:manage')")
  EventResponse rsvp(@PathVariable UUID id, @Valid @RequestBody RsvpRequest r) {
    return EventResponse.from(events.rsvp(id, r.flatId(), r.going() == null || r.going(),
        r.headcount() == null ? 1 : r.headcount()));
  }

  @GetMapping("/{id}/registrations")
  @PreAuthorize("@perm.has('event:manage')")
  List<RegistrationResponse> registrations(@PathVariable UUID id) {
    return events.registrations(id).stream().map(x -> new RegistrationResponse(x.getUserId(), x.getFlatId(),
        x.getStatus(), x.getHeadcount(), x.getUpdatedAt())).toList();
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("@perm.has('event:manage')")
  EventResponse cancel(@PathVariable UUID id) {
    return EventResponse.from(events.cancel(id));
  }
}
