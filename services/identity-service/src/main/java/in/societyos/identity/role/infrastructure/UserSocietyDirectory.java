package in.societyos.identity.role.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Societies and role codes of one user across all tenants, via the SECURITY DEFINER function
 * {@code identity_user_societies} (see V1__identity.sql). Used only to build tokens.
 */
@Component
public class UserSocietyDirectory {

  private final JdbcTemplate jdbc;

  public UserSocietyDirectory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** society id → role codes, in a stable order. */
  public Map<UUID, Set<String>> societiesOf(UUID userId) {
    Map<UUID, Set<String>> result = new LinkedHashMap<>();
    jdbc.query(
        "select society_id, role_code from identity_user_societies(?) order by society_id, role_code",
        rs -> {
          result.computeIfAbsent(rs.getObject("society_id", UUID.class), k -> new TreeSet<>())
              .add(rs.getString("role_code"));
        },
        userId);
    return result;
  }
}
