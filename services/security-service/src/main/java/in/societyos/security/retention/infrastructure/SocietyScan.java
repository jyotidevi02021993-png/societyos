package in.societyos.security.retention.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which societies have work for a job. Uses the migration-owned SECURITY DEFINER functions that
 * return only society ids across tenants; the jobs then open one RLS-scoped transaction per society.
 */
@Component
public class SocietyScan {

  private final JdbcTemplate jdbc;

  public SocietyScan(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> withExpiredEntries(Instant now) {
    return jdbc.queryForList("select society_id from security_societies_with_expired_entries(?)", UUID.class,
        Timestamp.from(now));
  }

  public List<UUID> withExpiredPasses(Instant now) {
    return jdbc.queryForList("select society_id from security_societies_with_expired_passes(?)", UUID.class,
        Timestamp.from(now));
  }

  public List<UUID> known() {
    return jdbc.queryForList("select society_id from security_known_societies()", UUID.class);
  }
}
