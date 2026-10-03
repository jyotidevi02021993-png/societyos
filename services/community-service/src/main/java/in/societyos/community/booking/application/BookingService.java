package in.societyos.community.booking.application;

import in.societyos.community.booking.domain.Booking;
import in.societyos.community.booking.domain.BookingEvents;
import in.societyos.community.booking.domain.BookingPolicy;
import in.societyos.community.booking.infrastructure.BookingRepository;
import in.societyos.community.directory.application.DirectoryService;
import in.societyos.community.directory.application.ResidentAccess;
import in.societyos.community.directory.domain.FacilityRef;
import in.societyos.community.notification.application.CommunityNotifier;
import in.societyos.community.notification.domain.NotificationRequested;
import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.core.tenant.TenantContext;
import in.societyos.community.platform.events.DomainEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Facility bookings under the facility's booking rules; residents book only for their own flats. */
@Service
public class BookingService {

  public static final String MANAGE = "booking:manage";

  public record BookingView(Booking booking, String facilityName, String flatLabel) {}

  /** One slot of a day: {@code available} places (1/0 for an exclusive facility). */
  public record Slot(Instant startsAt, Instant endsAt, int booked, int available) {}

  private final BookingRepository bookings;
  private final DirectoryService directory;
  private final ResidentAccess access;
  private final CommunityNotifier notifier;
  private final DomainEvents events;
  private final Clock clock;
  private final ZoneId zone;

  public BookingService(BookingRepository bookings, DirectoryService directory, ResidentAccess access,
      CommunityNotifier notifier, DomainEvents events, Clock clock, ZoneId societyZone) {
    this.bookings = bookings;
    this.directory = directory;
    this.access = access;
    this.notifier = notifier;
    this.events = events;
    this.clock = clock;
    this.zone = societyZone;
  }

  @Transactional
  public BookingView book(UUID facilityId, UUID flatId, Instant startsAt, Instant endsAt, int guests) {
    access.requireFlat(flatId, MANAGE);
    UUID me = access.currentUser();
    if (startsAt == null || endsAt == null) {
      throw ProblemException.badRequest("INVALID_TIMES", "startsAt and endsAt are required");
    }
    FacilityRef f = directory.lockFacility(facilityId); // serialises bookings of this facility
    BookingPolicy.Week week = BookingPolicy.weekOf(startsAt, zone);
    int thisWeek = (int) bookings.countForFlat(facilityId, flatId, week.start(), week.end());
    List<BookingPolicy.Existing> overlapping = bookings.overlapping(facilityId, startsAt, endsAt).stream().map(Booking::asExisting).toList();
    long charge = BookingPolicy.check(policy(f), startsAt, endsAt, guests, clock.instant(), zone, thisWeek, overlapping);

    Booking b = new Booking(facilityId, flatId, me, startsAt, endsAt, guests, f.isExclusive(), charge);
    try {
      bookings.saveAndFlush(b);
    } catch (DataIntegrityViolationException e) {
      throw ProblemException.conflict("SLOT_TAKEN", "That time is already booked");
    }
    events.publish(BookingEvents.requested(b));
    events.publish(BookingEvents.confirmed(b));
    notifyFlat(b, f, "community.booking.confirmed");
    return view(b, f);
  }

  @Transactional
  public BookingView cancel(UUID id, String reason) {
    Booking b = bookings.findByIdAndSocietyId(id, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("booking", id));
    boolean manager = access.has(MANAGE);
    if (!manager) {
      access.requireOwnFlat(b.getFlatId());
    }
    b.cancel(clock.instant(), reason == null ? null : reason.trim(), manager);
    events.publish(BookingEvents.cancelled(b));
    FacilityRef f = directory.facility(b.getFacilityId());
    notifyFlat(b, f, "community.booking.cancelled");
    return view(b, f);
  }

  /** Upcoming bookings: one flat (residents: their own), or every flat for managers. */
  @Transactional(readOnly = true)
  public List<BookingView> upcoming(UUID flatId) {
    UUID society = TenantContext.activeSocietyId();
    Instant now = clock.instant();
    List<Booking> list;
    if (flatId != null) {
      access.requireFlat(flatId, MANAGE);
      list = bookings.findTop200BySocietyIdAndFlatIdInAndEndsAtAfterOrderByStartsAtAsc(society, List.of(flatId), now);
    } else if (access.has(MANAGE)) {
      list = bookings.findTop500BySocietyIdAndEndsAtAfterOrderByStartsAtAsc(society, now);
    } else {
      Set<UUID> mine = directory.flatsOf(access.currentUser());
      list = mine.isEmpty() ? List.of()
          : bookings.findTop200BySocietyIdAndFlatIdInAndEndsAtAfterOrderByStartsAtAsc(society, mine, now);
    }
    Map<UUID, String> labels = directory.flatLabels(list.stream().map(Booking::getFlatId).distinct().toList());
    Map<UUID, String> names = new java.util.HashMap<>();
    directory.facilities().forEach(f -> names.put(f.getId(), f.getName()));
    return list.stream().map(b -> new BookingView(b, names.get(b.getFacilityId()), labels.get(b.getFlatId()))).toList();
  }

  @Transactional(readOnly = true)
  public List<FacilityRef> facilities() {
    return directory.facilities();
  }

  /** The slots of one local day with how many places are left. */
  @Transactional(readOnly = true)
  public List<Slot> availability(UUID facilityId, LocalDate date) {
    FacilityRef f = directory.facility(facilityId);
    Instant dayStart = date.atStartOfDay(zone).toInstant();
    Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
    List<Booking> taken = bookings.overlapping(facilityId, dayStart, dayEnd);
    List<Slot> slots = new ArrayList<>();
    Duration step = Duration.ofMinutes(f.getSlotMinutes());
    for (Instant s = dayStart; s.isBefore(dayEnd); s = s.plus(step)) {
      Instant e = s.plus(step);
      final Instant from = s;
      int used = taken.stream().filter(b -> b.getStartsAt().isBefore(e) && b.getEndsAt().isAfter(from))
          .mapToInt(Booking::getGuests).sum();
      boolean any = taken.stream().anyMatch(b -> b.getStartsAt().isBefore(e) && b.getEndsAt().isAfter(from));
      int available = f.isExclusive() ? (any ? 0 : 1) : Math.max(0, f.getCapacity() - used);
      slots.add(new Slot(s, e, used, available));
    }
    return slots;
  }

  private void notifyFlat(Booking b, FacilityRef f, String template) {
    notifier.notify(List.of(b.getUserId()), List.of(), NotificationRequested.BOOKING, template,
        Map.of("facility", f.getName(), "startsAt", b.getStartsAt().toString(), "bookingId", b.getId().toString(),
            "chargeRupees", rupees(b.getChargePaise())),
        template + ":" + b.getId());
  }

  private static String rupees(long paise) {
    return paise % 100 == 0 ? String.valueOf(paise / 100) : String.format("%d.%02d", paise / 100, paise % 100);
  }

  private BookingView view(Booking b, FacilityRef f) {
    return new BookingView(b, f.getName(), directory.flatLabels(List.of(b.getFlatId())).get(b.getFlatId()));
  }

  private static BookingPolicy.Facility policy(FacilityRef f) {
    return new BookingPolicy.Facility(f.isActive(), f.isExclusive(), f.getCapacity(), f.getSlotMinutes(),
        f.getMaxAdvanceDays(), f.getMaxPerFlatPerWeek(), f.isChargeable(), f.getChargePaise());
  }
}
