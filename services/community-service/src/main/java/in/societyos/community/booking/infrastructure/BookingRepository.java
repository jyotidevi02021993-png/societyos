package in.societyos.community.booking.infrastructure;

import in.societyos.community.booking.domain.Booking;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

  Optional<Booking> findByIdAndSocietyId(UUID id, UUID societyId);

  /** Confirmed bookings of a facility overlapping [from, to). */
  @Query("""
      select b from Booking b where b.facilityId = :facility and b.status = 'CONFIRMED'
      and b.startsAt < :to and b.endsAt > :from order by b.startsAt""")
  List<Booking> overlapping(@Param("facility") UUID facilityId, @Param("from") Instant from, @Param("to") Instant to);

  @Query("""
      select count(b) from Booking b where b.facilityId = :facility and b.flatId = :flat
      and b.status = 'CONFIRMED' and b.startsAt >= :from and b.startsAt < :to""")
  long countForFlat(@Param("facility") UUID facilityId, @Param("flat") UUID flatId, @Param("from") Instant from,
      @Param("to") Instant to);

  List<Booking> findTop200BySocietyIdAndFlatIdInAndEndsAtAfterOrderByStartsAtAsc(UUID societyId,
      Collection<UUID> flatIds, Instant after);

  List<Booking> findTop500BySocietyIdAndEndsAtAfterOrderByStartsAtAsc(UUID societyId, Instant after);
}
