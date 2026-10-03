package in.societyos.identity.platform.events.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Deletes outbox rows after 3 days and inbox rows after 30 days (longer than Kafka retention). */
public class OutboxCleanup {

  private final JdbcTemplate jdbc;

  public OutboxCleanup(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Scheduled(cron = "${sos.outbox.cleanup-cron:0 15 3 * * *}")
  public void purge() {
    jdbc.update("delete from outbox_event where created_at < now() - interval '3 days'");
    jdbc.update("delete from inbox_event where processed_at < now() - interval '30 days'");
  }
}
