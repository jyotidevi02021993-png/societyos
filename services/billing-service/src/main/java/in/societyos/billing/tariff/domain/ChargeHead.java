package in.societyos.billing.tariff.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A recurring charge of the society (maintenance, sinking fund, club...). {@code flat_type_rates}
 * is the JSON form of {@code {"2BHK": 250000}} (paise per flat of that type).
 */
@Entity
@Table(name = "charge_head")
public class ChargeHead extends TenantEntity {

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String basis;

  @Column(name = "rate_paise", nullable = false)
  private long ratePaise;

  @Column(name = "flat_type_rates", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String flatTypeRatesJson = "{}";

  @Column(name = "gst_applicable", nullable = false)
  private boolean gstApplicable;

  @Column(name = "applies_to_vacant", nullable = false)
  private boolean appliesToVacant;

  @Column(nullable = false)
  private boolean active = true;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  protected ChargeHead() {}

  public ChargeHead(String code) {
    this.code = code;
  }

  public void update(String name, TariffCalculator.Basis basis, long ratePaise, String flatTypeRatesJson,
      boolean gstApplicable, boolean appliesToVacant, boolean active, int sortOrder) {
    this.name = name;
    this.basis = basis.name();
    this.ratePaise = ratePaise;
    this.flatTypeRatesJson = flatTypeRatesJson;
    this.gstApplicable = gstApplicable;
    this.appliesToVacant = appliesToVacant;
    this.active = active;
    this.sortOrder = sortOrder;
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public TariffCalculator.Basis getBasis() { return TariffCalculator.Basis.valueOf(basis); }
  public long getRatePaise() { return ratePaise; }
  public String getFlatTypeRatesJson() { return flatTypeRatesJson; }
  public boolean isGstApplicable() { return gstApplicable; }
  public boolean isAppliesToVacant() { return appliesToVacant; }
  public boolean isActive() { return active; }
  public int getSortOrder() { return sortOrder; }
}
