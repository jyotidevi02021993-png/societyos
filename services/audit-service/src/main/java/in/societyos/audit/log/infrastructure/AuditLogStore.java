package in.societyos.audit.log.infrastructure;

import in.societyos.audit.log.domain.AuditEntry;
import in.societyos.audit.log.domain.AuditFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDBC access to the partitioned, append-only {@code audit_log}. There is deliberately no update or
 * delete method; the database refuses them anyway. Runs inside the caller's transaction, so the
 * tenant's RLS settings apply.
 */
@Repository
public class AuditLogStore {

  public static final String GENESIS = "GENESIS";

  private static final String COLUMNS =
      "id, society_id, seq, event_id, occurred_at, recorded_at, source, context, type, subject, subject_type,"
          + " subject_id, actor_id, actor_type, payload::text as payload, payload_trimmed, prev_hash, hash";

  private static final String INSERT = """
      insert into audit_log (id, society_id, seq, event_id, occurred_at, recorded_at, source, context, type, subject,
          subject_type, subject_id, actor_id, actor_type, payload, payload_trimmed, prev_hash, hash)
      select v.id, v.society_id, v.seq, v.event_id, v.occurred_at, v.recorded_at, v.source, v.context, v.type,
          v.subject, v.subject_type, v.subject_id, v.actor_id, v.actor_type, v.payload, v.trimmed, v.prev_hash,
          audit_row_hash(v.prev_hash, v.seq, v.event_id, v.society_id, v.occurred_at, v.type, v.subject,
                         v.actor_id, v.payload)
        from (select ?::uuid as id, ?::uuid as society_id, ?::bigint as seq, ?::uuid as event_id,
                     ?::timestamptz as occurred_at, ?::timestamptz as recorded_at, ?::text as source,
                     ?::text as context, ?::text as type, ?::text as subject, ?::text as subject_type,
                     ?::uuid as subject_id, ?::uuid as actor_id, ?::text as actor_type, ?::jsonb as payload,
                     ?::boolean as trimmed, ?::text as prev_hash) v
      returning hash
      """;

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public AuditLogStore(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public record ChainHead(long seq, String hash) {}

  /** Locks the society's chain head (creating it on first use) for the rest of the transaction. */
  public ChainHead lockChain(UUID societyId) {
    jdbc.update("insert into audit_chain (society_id, last_seq, last_hash) values (?, 0, ?) on conflict do nothing",
        societyId, GENESIS);
    return jdbc.queryForObject("select last_seq, last_hash from audit_chain where society_id = ? for update",
        (rs, i) -> new ChainHead(rs.getLong(1), rs.getString(2)), societyId);
  }

  /** Appends {@code e} after {@code head}; returns the new head. */
  public ChainHead append(AuditEntry e, ChainHead head) {
    long seq = head.seq() + 1;
    String hash = jdbc.queryForObject(INSERT, String.class, e.id(), e.societyId(), seq, e.eventId(),
        ts(e.occurredAt()), ts(e.recordedAt()), e.source(), e.context(), e.type(), e.subject(), e.subjectType(),
        e.subjectId(), e.actorId(), e.actorType(), e.payload().toString(), e.payloadTrimmed(), head.hash());
    jdbc.update("update audit_chain set last_seq = ?, last_hash = ?, updated_at = now() where society_id = ?",
        seq, hash, e.societyId());
    return new ChainHead(seq, hash);
  }

  /** Platform-level events (no society). Duplicate event ids are ignored. */
  public void appendPlatform(AuditEntry e) {
    jdbc.update("""
        insert into platform_audit_log (id, event_id, occurred_at, recorded_at, source, context, type, subject,
            actor_id, actor_type, payload, payload_trimmed)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) on conflict (event_id) do nothing
        """, e.id(), e.eventId(), ts(e.occurredAt()), ts(e.recordedAt()), e.source(), e.context(), e.type(),
        e.subject(), e.actorId(), e.actorType(), e.payload().toString(), e.payloadTrimmed());
  }

  /** Newest first; {@code (beforeAt, beforeId)} is the cursor of the previous page's last row. */
  public List<AuditEntry> search(UUID societyId, AuditFilter f, Instant beforeAt, UUID beforeId, int limit) {
    List<Object> args = new ArrayList<>();
    String where = where(societyId, f, args);
    if (beforeAt != null && beforeId != null) {
      where += " and (occurred_at, id) < (?, ?)";
      args.add(ts(beforeAt));
      args.add(beforeId);
    }
    args.add(limit);
    return jdbc.query("select " + COLUMNS + " from audit_log where " + where
        + " order by occurred_at desc, id desc limit ?", mapper(), args.toArray());
  }

  /** Streams matching rows (newest first, at most {@code max}) to {@code sink}; returns the count. */
  public int stream(UUID societyId, AuditFilter f, int max, Consumer<AuditEntry> sink) {
    List<Object> args = new ArrayList<>();
    String where = where(societyId, f, args);
    args.add(max);
    int[] n = {0};
    RowMapper<AuditEntry> m = mapper();
    jdbc.query("select " + COLUMNS + " from audit_log where " + where + " order by occurred_at desc, id desc limit ?",
        rs -> {
          sink.accept(m.mapRow(rs, n[0]++));
        }, args.toArray());
    return n[0];
  }

  public record Verification(long checked, Long firstSeq, Long lastSeq, Long brokenAtSeq, long lastChainSeq) {}

  /** Recomputes hashes and links for {@code seq} in [from, to] of one society. */
  public Verification verify(UUID societyId, long fromSeq, long toSeq) {
    var row = jdbc.queryForMap("""
        select count(*) as checked, min(seq) as first_seq, max(seq) as last_seq,
               min(seq) filter (where not ok) as broken_at
          from (select seq, coalesce(
                       hash = audit_row_hash(prev_hash, seq, event_id, society_id, occurred_at, type, subject,
                                             actor_id, payload)
                       and (prev_link is null and (seq > 1 or prev_hash = 'GENESIS') or prev_hash = prev_link)
                       and (prev_seq is null or prev_seq = seq - 1), false) as ok
                  from (select l.*, lag(hash) over (order by seq) as prev_link,
                               lag(seq) over (order by seq) as prev_seq
                          from audit_log l where society_id = ? and seq between ? and ?) chain) checked
        """, societyId, fromSeq, toSeq);
    Long last = jdbc.query("select last_seq from audit_chain where society_id = ?",
        rs -> rs.next() ? rs.getLong(1) : 0L, societyId);
    return new Verification(((Number) row.get("checked")).longValue(), toLong(row.get("first_seq")),
        toLong(row.get("last_seq")), toLong(row.get("broken_at")), last == null ? 0 : last);
  }

  /** Makes sure the month's partition exists (SECURITY DEFINER function owned by the migration role). */
  public String ensurePartition(LocalDate month) {
    return jdbc.queryForObject("select audit_ensure_partition(?)", String.class, java.sql.Date.valueOf(month));
  }

  private static String where(UUID societyId, AuditFilter f, List<Object> args) {
    StringBuilder w = new StringBuilder("society_id = ?");
    args.add(societyId);
    if (f.type() != null && !f.type().isBlank()) {
      if (f.type().endsWith("*")) {
        w.append(" and type like ?");
        args.add(f.type().substring(0, f.type().length() - 1).replace("%", "").replace("_", "\\_") + "%");
      } else {
        w.append(" and type = ?");
        args.add(f.type());
      }
    }
    if (f.context() != null && !f.context().isBlank()) {
      w.append(" and context = ?");
      args.add(f.context());
    }
    if (f.actorId() != null) {
      w.append(" and actor_id = ?");
      args.add(f.actorId());
    }
    if (f.subjectType() != null && !f.subjectType().isBlank()) {
      w.append(" and subject_type = ?");
      args.add(f.subjectType());
    }
    if (f.subjectId() != null) {
      w.append(" and subject_id = ?");
      args.add(f.subjectId());
    }
    if (f.from() != null) {
      w.append(" and occurred_at >= ?");
      args.add(ts(f.from()));
    }
    if (f.to() != null) {
      w.append(" and occurred_at < ?");
      args.add(ts(f.to()));
    }
    return w.toString();
  }

  private RowMapper<AuditEntry> mapper() {
    return (ResultSet rs, int i) -> new AuditEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("society_id", UUID.class),
        rs.getLong("seq"),
        rs.getObject("event_id", UUID.class),
        instant(rs, "occurred_at"),
        instant(rs, "recorded_at"),
        rs.getString("source"),
        rs.getString("context"),
        rs.getString("type"),
        rs.getString("subject"),
        rs.getString("subject_type"),
        rs.getObject("subject_id", UUID.class),
        rs.getObject("actor_id", UUID.class),
        rs.getString("actor_type"),
        json.readTree(rs.getString("payload")),
        rs.getBoolean("payload_trimmed"),
        rs.getString("prev_hash"),
        rs.getString("hash"));
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Long toLong(Object o) {
    return o == null ? null : ((Number) o).longValue();
  }
}
