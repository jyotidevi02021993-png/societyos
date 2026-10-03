package in.societyos.utility.meter.infrastructure;

import in.societyos.utility.meter.domain.Reading;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReadingRepository extends JpaRepository<Reading, UUID> {

  /** The reading just before {@code at} on the same meter (the one a new reading compares with). */
  Optional<Reading> findFirstByMeterIdAndAtLessThanOrderByAtDesc(UUID meterId, Instant at);

  /** The reading just after {@code at}: a back-dated reading must not exceed it on cumulative meters. */
  Optional<Reading> findFirstByMeterIdAndAtGreaterThanOrderByAtAsc(UUID meterId, Instant at);

  @Query("""
      select r from Reading r
      where (:meterId is null or r.meterId = :meterId)
        and r.at >= :from and r.at < :to
        and (:anomalyOnly = false or r.anomaly = true)
      order by r.at desc, r.id desc
      """)
  List<Reading> search(@Param("meterId") UUID meterId, @Param("from") Instant from, @Param("to") Instant to,
      @Param("anomalyOnly") boolean anomalyOnly, Pageable page);

  long countByAtGreaterThanEqualAndAtLessThan(Instant from, Instant to);

  long countByAtGreaterThanEqualAndAtLessThanAndAnomalyTrue(Instant from, Instant to);
}
