package in.societyos.billing.jobs.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which societies have work for a job. Uses migration-owned SECURITY DEFINER functions that
 * return only society ids across tenants; the jobs then open one RLS-scoped transaction per society.
 */
@Component
public class SocietyScan {

  private final JdbcTemplate jdbc;

  public SocietyScan(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> withOpenBills() {
    return jdbc.queryForList("select society_id from billing_societies_with_open_bills()", UUID.class);
  }

  public List<UUID> withPendingPayments(Instant before) {
    return jdbc.queryForList("select society_id from billing_societies_with_pending_payments(?)", UUID.class,
        Timestamp.from(before));
  }
}
