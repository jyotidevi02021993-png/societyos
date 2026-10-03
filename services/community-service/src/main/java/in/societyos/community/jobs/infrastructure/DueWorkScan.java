package in.societyos.community.jobs.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Finds societies with due work through the SECURITY DEFINER function (ids only, no rows). */
@Component
public class DueWorkScan {

  private final JdbcTemplate jdbc;

  public DueWorkScan(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> societiesWithDueWork(Instant now) {
    return jdbc.queryForList("select society_id from community_societies_with_due_work(?)", UUID.class,
        Timestamp.from(now));
  }
}
