package in.societyos.identity.role.domain;

import in.societyos.identity.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A society's role: a named bundle of {@code module:action} permissions, editable by the society. */
@Entity
@Table(name = "role")
public class Role extends TenantEntity {

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "permissions", nullable = false, columnDefinition = "text[]")
  private String[] permissions = new String[0];

  @Column(name = "is_system", nullable = false)
  private boolean system;

  protected Role() {}

  public static Role fromTemplate(String code, String name, Collection<String> permissions) {
    Role r = new Role();
    r.code = code;
    r.name = name;
    r.permissions = new TreeSet<>(permissions).toArray(String[]::new);
    r.system = true;
    return r;
  }

  public void replacePermissions(Collection<String> newPermissions) {
    this.permissions = new TreeSet<>(newPermissions).toArray(String[]::new);
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public List<String> getPermissions() {
    return Arrays.asList(permissions);
  }

  public boolean isSystem() {
    return system;
  }
}
