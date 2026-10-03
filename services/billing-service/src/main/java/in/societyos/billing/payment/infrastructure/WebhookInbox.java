package in.societyos.billing.payment.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code webhook_event} dedup by (provider, event id), and the order → society lookup. */
@Component
public class WebhookInbox {

  private final JdbcTemplate jdbc;

  public WebhookInbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** True the first time this event is seen; false for a redelivery. Runs in the caller's transaction. */
  public boolean record(String provider, String eventId, UUID societyId, String payload) {
    return jdbc.update("""
        insert into webhook_event (provider, event_id, society_id, payload) values (?, ?, ?, ?::jsonb)
        on conflict (provider, event_id) do nothing
        """, provider, eventId, societyId, payload) == 1;
  }

  /** The society owning a gateway order (SECURITY DEFINER lookup: the webhook has no tenant). */
  public Optional<UUID> societyOfOrder(String orderId) {
    return Optional.ofNullable(jdbc.queryForObject("select billing_society_of_order(?)", UUID.class, orderId));
  }
}
