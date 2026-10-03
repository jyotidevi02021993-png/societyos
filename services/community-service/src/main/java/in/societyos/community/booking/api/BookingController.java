package in.societyos.community.booking.api;

import in.societyos.community.booking.application.BookingService;
import in.societyos.community.booking.application.BookingService.BookingView;
import in.societyos.community.booking.application.BookingService.Slot;
import in.societyos.community.booking.domain.Booking;
import in.societyos.community.directory.domain.FacilityRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BookingController {

  private final BookingService bookings;

  BookingController(BookingService bookings) {
    this.bookings = bookings;
  }

  record BookingRequest(@NotNull UUID facilityId, @NotNull UUID flatId, @NotNull Instant startsAt,
      @NotNull Instant endsAt, Integer guests) {}

  record CancelRequest(@Size(max = 500) String reason) {}

  record BookingResponse(UUID id, UUID facilityId, String facilityName, UUID flatId, String flatLabel, UUID userId,
      Instant startsAt, Instant endsAt, int guests, String status, long chargePaise, Instant cancelledAt,
      String cancelReason) {
    static BookingResponse from(BookingView v) {
      Booking b = v.booking();
      return new BookingResponse(b.getId(), b.getFacilityId(), v.facilityName(), b.getFlatId(), v.flatLabel(),
          b.getUserId(), b.getStartsAt(), b.getEndsAt(), b.getGuests(), b.getStatus(), b.getChargePaise(),
          b.getCancelledAt(), b.getCancelReason());
    }
  }

  record BookingRulesDto(int slotMinutes, int maxAdvanceDays, int maxPerFlatPerWeek) {}

  record FacilityResponse(UUID id, String kind, String name, int capacity, boolean exclusive, boolean chargeable,
      long chargePaise, BookingRulesDto bookingRules, String status) {
    static FacilityResponse from(FacilityRef f) {
      return new FacilityResponse(f.getId(), f.getKind(), f.getName(), f.getCapacity(), f.isExclusive(),
          f.isChargeable(), f.getChargePaise(),
          new BookingRulesDto(f.getSlotMinutes(), f.getMaxAdvanceDays(), f.getMaxPerFlatPerWeek()), f.getStatus());
    }
  }

  @GetMapping("/v1/facilities")
  @PreAuthorize("@perm.hasAny('booking:create', 'booking:manage')")
  List<FacilityResponse> facilities() {
    return bookings.facilities().stream().map(FacilityResponse::from).toList();
  }

  @GetMapping("/v1/facilities/{id}/availability")
  @PreAuthorize("@perm.hasAny('booking:create', 'booking:manage')")
  List<Slot> availability(@PathVariable UUID id, @RequestParam LocalDate date) {
    return bookings.availability(id, date);
  }

  @PostMapping("/v1/bookings")
  @PreAuthorize("@perm.hasAny('booking:create', 'booking:manage')")
  ResponseEntity<BookingResponse> book(@Valid @RequestBody BookingRequest r) {
    BookingView v = bookings.book(r.facilityId(), r.flatId(), r.startsAt(), r.endsAt(),
        r.guests() == null ? 1 : r.guests());
    return ResponseEntity.created(URI.create("/v1/bookings/" + v.booking().getId())).body(BookingResponse.from(v));
  }

  @GetMapping("/v1/bookings")
  @PreAuthorize("@perm.hasAny('booking:create', 'booking:manage')")
  List<BookingResponse> upcoming(@RequestParam(required = false) UUID flatId) {
    return bookings.upcoming(flatId).stream().map(BookingResponse::from).toList();
  }

  @PostMapping("/v1/bookings/{id}/cancel")
  @PreAuthorize("@perm.hasAny('booking:create', 'booking:manage')")
  BookingResponse cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelRequest r) {
    return BookingResponse.from(bookings.cancel(id, r == null ? null : r.reason()));
  }
}
