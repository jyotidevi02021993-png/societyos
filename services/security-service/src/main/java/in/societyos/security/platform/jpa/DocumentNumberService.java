package in.societyos.security.platform.jpa;

import in.societyos.security.platform.core.tenant.TenantContext;
import java.time.Clock;
import java.time.Year;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human-facing numbers per society and year, e.g. {@code JC-2026-000123}. Gap-free within a
 * committed transaction: the counter row is locked until the caller's transaction ends.
 */
public class DocumentNumberService {

  private static final String SQL =
      """
      insert into document_sequence (society_id, kind, year, next_value)
      values (?, ?, ?, 1)
      on conflict (society_id, kind, year)
      do update set next_value = document_sequence.next_value + 1
      returning next_value
      """;

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final ZoneId zone;

  public DocumentNumberService(JdbcTemplate jdbc, Clock clock, ZoneId zone) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.zone = zone;
  }

  /** Next number for {@code kind} (e.g. "JC", "CMP", "RCPT") in the active society. */
  @Transactional(propagation = Propagation.MANDATORY)
  public String next(String kind) {
    UUID societyId = TenantContext.activeSocietyId();
    int year = Year.now(clock.withZone(zone)).getValue();
    Long value = jdbc.queryForObject(SQL, Long.class, societyId, kind, year);
    return "%s-%d-%06d".formatted(kind, year, value);
  }
}
