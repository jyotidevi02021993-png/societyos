package in.societyos.inventory.stock.domain;

import in.societyos.inventory.platform.core.error.ProblemException;
import in.societyos.inventory.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Quantity on hand of one item in one store, with its moving-average cost. The balance can never go
 * below zero: {@link #apply} refuses the movement instead (the database CHECK is the second line).
 */
@Entity
@Table(name = "stock_level")
public class StockLevel extends TenantEntity {

  @Column(name = "store_id", nullable = false, updatable = false)
  private UUID storeId;
  @Column(name = "item_id", nullable = false, updatable = false)
  private UUID itemId;
  @Column(nullable = false)
  private int qty;
  @Column(name = "avg_cost_paise", nullable = false)
  private long avgCostPaise;
  @Column(name = "reorder_level")
  private Integer reorderLevel;
  @Column(name = "low_alerted", nullable = false)
  private boolean lowAlerted;

  protected StockLevel() {}

  public static StockLevel empty(UUID storeId, UUID itemId) {
    StockLevel l = new StockLevel();
    l.storeId = storeId;
    l.itemId = itemId;
    return l;
  }

  /**
   * Applies a signed quantity change and returns the new balance. An incoming movement with a cost
   * updates the moving-average cost; an outgoing one leaves it unchanged.
   *
   * @throws ProblemException {@code INSUFFICIENT_STOCK} if the balance would become negative
   */
  public int apply(int delta, long unitCostPaise) {
    if (delta == 0) {
      throw ProblemException.badRequest("INVALID_QTY", "Quantity must not be zero");
    }
    long next = (long) qty + delta;
    if (next < 0) {
      throw new ProblemException("INSUFFICIENT_STOCK", org.springframework.http.HttpStatus.CONFLICT,
          "Only " + qty + " in stock, cannot take out " + (-delta),
          java.util.Map.of("available", qty, "requested", -delta));
    }
    if (next > Integer.MAX_VALUE) {
      throw ProblemException.badRequest("INVALID_QTY", "Quantity too large");
    }
    if (delta > 0 && unitCostPaise > 0) {
      avgCostPaise = (qty * avgCostPaise + (long) delta * unitCostPaise + next / 2) / next;
    }
    qty = (int) next;
    return qty;
  }

  public int effectiveReorderLevel(int itemDefault) {
    return reorderLevel != null ? reorderLevel : itemDefault;
  }

  /**
   * Returns true exactly once each time stock falls to or below the reorder level; rising above it
   * again re-arms the alert. A reorder level of 0 means "no alert".
   */
  public boolean checkLow(int itemDefault) {
    int level = effectiveReorderLevel(itemDefault);
    if (level > 0 && qty <= level) {
      if (!lowAlerted) {
        lowAlerted = true;
        return true;
      }
      return false;
    }
    lowAlerted = false;
    return false;
  }

  public void setReorderLevel(Integer level) {
    if (level != null && level < 0) {
      throw ProblemException.badRequest("INVALID_REORDER_LEVEL", "reorderLevel must not be negative");
    }
    this.reorderLevel = level;
  }

  public UUID getStoreId() { return storeId; }
  public UUID getItemId() { return itemId; }
  public int getQty() { return qty; }
  public long getAvgCostPaise() { return avgCostPaise; }
  public Integer getReorderLevel() { return reorderLevel; }
  public boolean isLowAlerted() { return lowAlerted; }
}
