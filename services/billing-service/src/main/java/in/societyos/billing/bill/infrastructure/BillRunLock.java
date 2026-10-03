package in.societyos.billing.bill.infrastructure;

import in.societyos.billing.platform.core.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code bill-run:<society>}: one bill run computes or publishes at a time per society (tx-scoped). */
@Component
public class BillRunLock {

  private final JdbcTemplate jdbc;

  public BillRunLock(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void acquire() {
    jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))",
        "bill-run:" + TenantContext.activeSocietyId());
  }
}
