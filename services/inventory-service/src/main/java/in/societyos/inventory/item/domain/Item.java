package in.societyos.inventory.item.domain;

import in.societyos.inventory.platform.core.error.ProblemException;
import in.societyos.inventory.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;

/**
 * A stocked item: a spare, a consumable or a tool. Its id is the {@code spareId} used by ticket-service
 * job cards and vendor-service PO lines.
 */
@Entity
@Table(name = "item")
public class Item extends TenantEntity {

  public static final Set<String> KINDS = Set.of("SPARE", "CONSUMABLE", "TOOL");

  @Column(nullable = false, updatable = false)
  private String code;
  @Column(nullable = false)
  private String name;
  @Column(nullable = false)
  private String kind = "SPARE";
  private String category;
  @Column(nullable = false)
  private String unit = "NOS";
  @Column(name = "default_reorder_level", nullable = false)
  private int defaultReorderLevel;
  @Column(name = "last_cost_paise", nullable = false)
  private long lastCostPaise;
  @Column(nullable = false)
  private String status = "ACTIVE";

  protected Item() {}

  public static Item create(String code, String name, String kind, String category, String unit, int reorderLevel) {
    if (code == null || name == null) {
      throw ProblemException.badRequest("INVALID_ITEM", "code and name are required");
    }
    Item i = new Item();
    i.code = code;
    i.describe(name, kind, category, unit, reorderLevel);
    return i;
  }

  public void describe(String name, String kind, String category, String unit, int reorderLevel) {
    if (name == null) {
      throw ProblemException.badRequest("INVALID_ITEM", "name is required");
    }
    if (kind != null && !KINDS.contains(kind)) {
      throw ProblemException.badRequest("INVALID_KIND", "kind is one of " + KINDS);
    }
    if (reorderLevel < 0) {
      throw ProblemException.badRequest("INVALID_REORDER_LEVEL", "reorderLevel must not be negative");
    }
    this.name = name;
    if (kind != null) this.kind = kind;
    this.category = category;
    if (unit != null) this.unit = unit;
    this.defaultReorderLevel = reorderLevel;
  }

  public void costSeen(long unitCostPaise) {
    if (unitCostPaise > 0) {
      lastCostPaise = unitCostPaise;
    }
  }

  public void deactivate() {
    status = "INACTIVE";
  }

  public boolean isActive() {
    return "ACTIVE".equals(status);
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public String getKind() { return kind; }
  public String getCategory() { return category; }
  public String getUnit() { return unit; }
  public int getDefaultReorderLevel() { return defaultReorderLevel; }
  public long getLastCostPaise() { return lastCostPaise; }
  public String getStatus() { return status; }
}
