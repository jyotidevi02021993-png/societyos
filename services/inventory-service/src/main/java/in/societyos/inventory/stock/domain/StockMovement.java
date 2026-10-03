package in.societyos.inventory.stock.domain;

import in.societyos.inventory.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One row of the append-only stock ledger. Every field is immutable once written. */
@Entity
@Table(name = "stock_movement")
public class StockMovement extends TenantEntity {

  public enum Kind { RECEIPT, ISSUE, RETURN, TRANSFER_OUT, TRANSFER_IN, ADJUSTMENT }

  @Column(name = "store_id", nullable = false, updatable = false)
  private UUID storeId;
  @Column(name = "item_id", nullable = false, updatable = false)
  private UUID itemId;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private Kind kind;
  @Column(name = "qty_delta", nullable = false, updatable = false)
  private int qtyDelta;
  @Column(name = "balance_after", nullable = false, updatable = false)
  private int balanceAfter;
  @Column(name = "unit_cost_paise", nullable = false, updatable = false)
  private long unitCostPaise;
  @Column(name = "ref_type", updatable = false)
  private String refType;
  @Column(name = "ref_id", updatable = false)
  private UUID refId;
  @Column(name = "job_card_id", updatable = false)
  private UUID jobCardId;
  @Column(name = "issued_to", updatable = false)
  private UUID issuedTo;
  @Column(columnDefinition = "text", updatable = false)
  private String reason;
  @Column(nullable = false, updatable = false)
  private Instant at;

  protected StockMovement() {}

  public StockMovement(UUID storeId, UUID itemId, Kind kind, int qtyDelta, int balanceAfter, long unitCostPaise,
      String refType, UUID refId, UUID jobCardId, UUID issuedTo, String reason, Instant at) {
    getId();
    this.storeId = storeId;
    this.itemId = itemId;
    this.kind = kind;
    this.qtyDelta = qtyDelta;
    this.balanceAfter = balanceAfter;
    this.unitCostPaise = Math.max(0, unitCostPaise);
    this.refType = refType;
    this.refId = refId;
    this.jobCardId = jobCardId;
    this.issuedTo = issuedTo;
    this.reason = reason;
    this.at = at;
  }

  public UUID getStoreId() { return storeId; }
  public UUID getItemId() { return itemId; }
  public Kind getKind() { return kind; }
  public int getQtyDelta() { return qtyDelta; }
  public int getBalanceAfter() { return balanceAfter; }
  public long getUnitCostPaise() { return unitCostPaise; }
  public String getRefType() { return refType; }
  public UUID getRefId() { return refId; }
  public UUID getJobCardId() { return jobCardId; }
  public UUID getIssuedTo() { return issuedTo; }
  public String getReason() { return reason; }
  public Instant getAt() { return at; }
}
