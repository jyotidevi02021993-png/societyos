package in.societyos.identity.role.infrastructure;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Read access to the global catalogue tables {@code role_template} and {@code permission}. */
@Component
public class RoleTemplates {

  public record Template(String code, String name, String description, List<String> permissions) {}

  public record Permission(String code, String module, String action, String description) {}

  private static final RowMapper<Template> TEMPLATE =
      (rs, i) -> new Template(rs.getString("code"), rs.getString("name"), rs.getString("description"), strings(rs.getArray("permissions")));

  private final JdbcTemplate jdbc;

  public RoleTemplates(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Template> all() {
    return jdbc.query("select code, name, description, permissions from role_template order by code", TEMPLATE);
  }

  public Optional<Template> byCode(String code) {
    return jdbc.query("select code, name, description, permissions from role_template where code = ?", TEMPLATE, code)
        .stream()
        .findFirst();
  }

  public List<Permission> permissions() {
    return jdbc.query(
        "select code, module, action, description from permission order by module, action",
        (rs, i) -> new Permission(rs.getString("code"), rs.getString("module"), rs.getString("action"), rs.getString("description")));
  }

  public Set<String> permissionCodes() {
    return new TreeSet<>(jdbc.queryForList("select code from permission", String.class));
  }

  private static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }
}
