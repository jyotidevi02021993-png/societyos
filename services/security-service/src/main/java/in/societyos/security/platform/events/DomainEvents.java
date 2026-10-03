package in.societyos.security.platform.events;

import in.societyos.security.platform.core.UuidV7;
import in.societyos.security.platform.core.tenant.Tenant;
import in.societyos.security.platform.core.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes domain events through the transactional outbox: the event is an {@code outbox_event}
 * row written in the caller's transaction, so it exists if and only if the business change
 * commits. Debezium (or the polling relay) moves it to Kafka.
 */
public class DomainEvents {

  static final String SCHEMA_BASE = "https://schemas.societyos.in/";

  private static final String INSERT =
      """
      insert into outbox_event (id, aggregate_type, aggregate_id, type, society_id, payload, created_at)
      values (?, ?, ?, ?, ?, ?::jsonb, ?)
      """;

  private final JdbcTemplate jdbc;
  private final JsonMapper mapper;
  private final String source;

  public DomainEvents(JdbcTemplate jdbc, JsonMapper mapper, String source) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.source = source;
  }

  /** Writes the event to the outbox of the active society. Requires an open transaction. */
  public UUID publish(DomainEvent event) {
    Tenant tenant = TenantContext.optional().orElse(Tenant.platform());
    return publish(event, tenant.activeSocietyId());
  }

  /** Writes a platform-level event (no society), e.g. {@code identity.user.registered}. */
  public UUID publishGlobal(DomainEvent event) {
    return publish(event, null);
  }

  private UUID publish(DomainEvent event, UUID societyId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "DomainEvents.publish must run inside a transaction: " + event.type());
    }
    Tenant tenant = TenantContext.optional().orElse(Tenant.platform());
    UUID id = UuidV7.next();
    Instant now = Instant.now();

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("specversion", "1.0");
    envelope.put("id", id);
    envelope.put("source", source);
    envelope.put("type", event.type());
    envelope.put(
        "dataschema",
        SCHEMA_BASE + event.context() + "/" + event.type() + "/" + event.schemaVersion() + ".json");
    envelope.put("time", now.toString());
    envelope.put("subject", event.subject());
    envelope.put("societyid", societyId);
    envelope.put("actorid", tenant.userId());
    envelope.put("actortype", tenant.actorType().name());
    envelope.put("data", event);

    jdbc.update(
        INSERT,
        id,
        event.context(),
        event.aggregateId(),
        event.type(),
        societyId,
        mapper.writeValueAsString(envelope),
        java.sql.Timestamp.from(now));
    return id;
  }
}
