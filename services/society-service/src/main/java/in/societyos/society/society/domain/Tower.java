package in.societyos.society.society.domain;

import in.societyos.society.platform.jpa.TenantEntity;
import in.societyos.society.platform.core.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "tower")
public class Tower extends TenantEntity {
  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String code;

  @Column(name = "floors_count", nullable = false)
  private int floorsCount;

  protected Tower() {}

  public Tower(String name, String code, int floorsCount) {
    super(UuidV7.next());
    this.name = name;
    this.code = code;
    this.floorsCount = floorsCount;
  }

  public void update(String name, String code, int floorsCount) {
    this.name = name;
    this.code = code;
    this.floorsCount = floorsCount;
  }

  public String getName() { return name; }
  public String getCode() { return code; }
  public int getFloorsCount() { return floorsCount; }
}
