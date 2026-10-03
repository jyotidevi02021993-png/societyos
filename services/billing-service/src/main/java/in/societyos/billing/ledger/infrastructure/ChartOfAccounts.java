package in.societyos.billing.ledger.infrastructure;

import in.societyos.billing.ledger.domain.Journal;
import in.societyos.billing.platform.core.UuidV7;
import in.societyos.billing.platform.core.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates the society's standard accounts on first use; concurrent callers never collide. */
@Component
public class ChartOfAccounts {

  private static final String SQL = """
      insert into ledger_account (id, society_id, code, name, kind, created_at, created_by, updated_at, version)
      values (?, ?, ?, ?, ?, ?, ?, ?, 0)
      on conflict (society_id, code) do nothing
      """;

  private final JdbcTemplate jdbc;

  public ChartOfAccounts(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void ensure() {
    Timestamp now = Timestamp.from(Instant.now());
    for (Journal.Account a : Journal.Account.values()) {
      jdbc.update(SQL, UuidV7.next(), TenantContext.activeSocietyId(), a.name(), a.title(), a.kind(), now,
          TenantContext.userId().orElse(null), now);
    }
  }
}
