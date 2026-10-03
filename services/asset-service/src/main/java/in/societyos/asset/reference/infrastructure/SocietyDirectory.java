package in.societyos.asset.reference.infrastructure;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The global {@code society_ref} table: which societies this service serves and their time
 * zones, so the scheduler can iterate them. Holds no tenant data, hence no RLS.
 */
@Repository
public class SocietyDirectory {

  public record SocietyZone(UUID societyId, ZoneId zone) {}

  private final JdbcTemplate jdbc;
  private final ZoneId defaultZone;
  private final Map<UUID, ZoneId> zones = new ConcurrentHashMap<>();

  public SocietyDirectory(JdbcTemplate jdbc, @Value("${sos.default-timezone:Asia/Kolkata}") String defaultZone) {
    this.jdbc = jdbc;
    this.defaultZone = ZoneId.of(defaultZone);
  }

  /** From {@code society.created}: inserts or refreshes name and time zone. */
  public void register(UUID societyId, String name, String timezone) {
    String zone = validZone(timezone).getId();
    jdbc.update("""
        insert into society_ref (society_id, name, timezone) values (?, ?, ?)
        on conflict (society_id) do update set name = excluded.name, timezone = excluded.timezone, updated_at = now()
        """, societyId, name, zone);
    zones.put(societyId, ZoneId.of(zone));
  }

  /** Makes sure a society that writes assets is known to the scheduler (idempotent). */
  public void ensure(UUID societyId) {
    if (zones.containsKey(societyId)) {
      return;
    }
    jdbc.update("insert into society_ref (society_id, timezone) values (?, ?) on conflict do nothing",
        societyId, defaultZone.getId());
    zoneOf(societyId);
  }

  public ZoneId zoneOf(UUID societyId) {
    return zones.computeIfAbsent(societyId, id -> {
      List<String> tz = jdbc.queryForList("select timezone from society_ref where society_id = ?", String.class, id);
      return tz.isEmpty() ? defaultZone : validZone(tz.getFirst());
    });
  }

  public List<SocietyZone> all() {
    return jdbc.query("select society_id, timezone from society_ref order by society_id",
        (rs, i) -> new SocietyZone(rs.getObject(1, UUID.class), validZone(rs.getString(2))));
  }

  private ZoneId validZone(String timezone) {
    return Optional.ofNullable(timezone).filter(t -> !t.isBlank()).flatMap(t -> {
      try {
        return Optional.of(ZoneId.of(t));
      } catch (RuntimeException e) {
        return Optional.empty();
      }
    }).orElse(defaultZone);
  }
}
