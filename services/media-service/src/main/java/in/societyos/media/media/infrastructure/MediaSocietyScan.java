package in.societyos.media.media.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Society ids with pending work, through the migration-owned SECURITY DEFINER function. */
@Component
public class MediaSocietyScan {

  private final JdbcTemplate jdbc;

  public MediaSocietyScan(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> withWork(Instant now) {
    return jdbc.queryForList("select society_id from media_societies_with_work(?)", UUID.class, Timestamp.from(now));
  }
}
