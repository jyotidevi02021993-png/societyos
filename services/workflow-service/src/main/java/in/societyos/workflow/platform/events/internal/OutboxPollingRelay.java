package in.societyos.workflow.platform.events.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fallback publisher for environments without Debezium ({@code sos.outbox.relay=polling}). Same
 * topic, key and headers as the Debezium Outbox Event Router, so consumers cannot tell the
 * difference. Rows are locked with SKIP LOCKED, so several replicas can run it safely.
 */
public class OutboxPollingRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxPollingRelay.class);

  private static final String SELECT =
      """
      select id, aggregate_type, aggregate_id, type, society_id, payload::text as payload
      from outbox_event
      where published_at is null
      order by created_at, id
      limit 200
      for update skip locked
      """;

  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate tx;

  public OutboxPollingRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.tx = tx;
  }

  record Row(UUID id, String context, String aggregateId, String type, String societyId, String payload) {}

  @Scheduled(fixedDelayString = "${sos.outbox.polling-interval:200}")
  public void relay() {
    Integer sent;
    do {
      sent = tx.execute(status -> relayBatch());
    } while (sent != null && sent == 200);
  }

  private int relayBatch() {
    List<Row> rows =
        jdbc.query(
            SELECT,
            (rs, i) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getString("type"),
                    rs.getString("society_id"),
                    rs.getString("payload")));
    for (Row row : rows) {
      var record =
          new ProducerRecord<>(OutboxTopics.topicFor(row.context()), row.aggregateId(), row.payload());
      record.headers().add("ce_id", bytes(row.id().toString()));
      record.headers().add("ce_type", bytes(row.type()));
      if (row.societyId() != null) {
        record.headers().add("ce_societyid", bytes(row.societyId()));
      }
      try {
        kafka.send(record).get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        log.warn("Outbox relay failed for {} ({}); will retry", row.id(), row.type(), e);
        throw new IllegalStateException(e); // roll back: rows stay unpublished
      }
      jdbc.update("update outbox_event set published_at = now() where id = ?", row.id());
    }
    return rows.size();
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
