package in.societyos.billing.expense.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** Money spent by the society (EXPENSE) or paid to a vendor (VENDOR_PAYMENT). */
@Entity
@Table(name = "expense")
public class Expense extends TenantEntity {

  @Column(nullable = false)
  private String kind;

  @Column(nullable = false)
  private String category;

  private String description;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(name = "paid_from", nullable = false)
  private String paidFrom;

  @Column(name = "spent_on", nullable = false)
  private LocalDate spentOn;

  @Column(name = "financial_year", nullable = false)
  private String financialYear;

  @Column(name = "asset_id")
  private UUID assetId;

  @Column(name = "vendor_id")
  private UUID vendorId;

  @Column(name = "tower_id")
  private UUID towerId;

  private String department;

  private String reference;

  protected Expense() {}

  public Expense(String kind, String category, String description, long amountPaise, String paidFrom,
      LocalDate spentOn, UUID assetId, UUID vendorId, UUID towerId, String department, String reference) {
    if (amountPaise <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    this.kind = kind;
    this.category = category;
    this.description = description;
    this.amountPaise = amountPaise;
    this.paidFrom = paidFrom;
    this.spentOn = spentOn;
    this.financialYear = FinancialYear.of(spentOn);
    this.assetId = assetId;
    this.vendorId = vendorId;
    this.towerId = towerId;
    this.department = department;
    this.reference = reference;
  }

  public String getKind() { return kind; }
  public String getCategory() { return category; }
  public String getDescription() { return description; }
  public long getAmountPaise() { return amountPaise; }
  public String getPaidFrom() { return paidFrom; }
  public LocalDate getSpentOn() { return spentOn; }
  public String getFinancialYear() { return financialYear; }
  public UUID getAssetId() { return assetId; }
  public UUID getVendorId() { return vendorId; }
  public UUID getTowerId() { return towerId; }
  public String getDepartment() { return department; }
  public String getReference() { return reference; }
}
