package in.societyos.billing.roster.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;

/**
 * Billing configuration of one society (id = society id). {@code billingDueDay} and
 * {@code lateFeeGraceDays} are copied from {@code society.settings.updated}; GST and the late-fee
 * rule are billing's own settings.
 */
@Entity
@Table(name = "billing_settings")
public class BillingSettings extends TenantEntity {

  public static final Set<String> LATE_FEE_KINDS = Set.of("NONE", "FIXED", "PERCENT");

  @Column(name = "billing_due_day", nullable = false)
  private int billingDueDay = 10;

  @Column(name = "late_fee_grace_days", nullable = false)
  private int lateFeeGraceDays = 15;

  @Column(name = "gst_registered", nullable = false)
  private boolean gstRegistered;

  @Column(name = "gst_rate_bps", nullable = false)
  private int gstRateBps = 1800;

  @Column(name = "gst_exemption_threshold_paise", nullable = false)
  private long gstExemptionThresholdPaise = 750_000;

  @Column(name = "late_fee_kind", nullable = false)
  private String lateFeeKind = "NONE";

  /** FIXED: paise; PERCENT: basis points of the overdue balance. */
  @Column(name = "late_fee_value", nullable = false)
  private long lateFeeValue;

  @Column(name = "reminder_days_before", nullable = false)
  private int reminderDaysBefore = 3;

  protected BillingSettings() {}

  public BillingSettings(UUID societyId) {
    super(societyId);
  }

  /** From society-service; values are clamped so a bad value upstream cannot poison the consumer. */
  public void applySociety(Integer billingDueDay, Integer lateFeeGraceDays) {
    if (billingDueDay != null) {
      this.billingDueDay = Math.clamp(billingDueDay, 1, 28);
    }
    if (lateFeeGraceDays != null) {
      this.lateFeeGraceDays = Math.clamp(lateFeeGraceDays, 0, 60);
    }
  }

  public void configure(boolean gstRegistered, int gstRateBps, long gstExemptionThresholdPaise, String lateFeeKind,
      long lateFeeValue, int reminderDaysBefore) {
    this.gstRegistered = gstRegistered;
    this.gstRateBps = gstRateBps;
    this.gstExemptionThresholdPaise = gstExemptionThresholdPaise;
    this.lateFeeKind = lateFeeKind;
    this.lateFeeValue = lateFeeValue;
    this.reminderDaysBefore = reminderDaysBefore;
  }

  public int getBillingDueDay() { return billingDueDay; }
  public int getLateFeeGraceDays() { return lateFeeGraceDays; }
  public boolean isGstRegistered() { return gstRegistered; }
  public int getGstRateBps() { return gstRateBps; }
  public long getGstExemptionThresholdPaise() { return gstExemptionThresholdPaise; }
  public String getLateFeeKind() { return lateFeeKind; }
  public long getLateFeeValue() { return lateFeeValue; }
  public int getReminderDaysBefore() { return reminderDaysBefore; }
}
