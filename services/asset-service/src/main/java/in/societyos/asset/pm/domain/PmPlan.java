package in.societyos.asset.pm.domain;

import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** A preventive-maintenance plan for one asset: calendar (daily … yearly) or usage-based. */
@Entity
@Table(name = "pm_plan")
public class PmPlan extends TenantEntity {

  @Column(name = "asset_id", nullable = false, updatable = false)
  private UUID assetId;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PmFrequency frequency;

  @Column(name = "anchor_on")
  private LocalDate anchorOn;

  @Column(name = "lead_days", nullable = false)
  private int leadDays;

  @Column(name = "next_due_on")
  private LocalDate nextDueOn;

  @Column(name = "usage_metric")
  private String usageMetric;

  @Column(name = "usage_interval")
  private BigDecimal usageInterval;

  @Column(name = "last_done_usage")
  private BigDecimal lastDoneUsage;

  @Column(name = "latest_usage")
  private BigDecimal latestUsage;

  @Column(name = "checklist", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String checklistJson;

  @Column(name = "checklist_template_id")
  private UUID checklistTemplateId;

  @Column(name = "assignee_user_id")
  private UUID assigneeUserId;

  @Column(name = "vendor_id")
  private UUID vendorId;

  @Column(nullable = false)
  private boolean active;

  protected PmPlan() {}

  /** Editable fields of a plan. */
  public record Details(
      String name,
      PmFrequency frequency,
      LocalDate anchorOn,
      int leadDays,
      String usageMetric,
      BigDecimal usageInterval,
      String checklistJson,
      UUID checklistTemplateId,
      UUID assigneeUserId,
      UUID vendorId) {

    public Details {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("name is required");
      }
      if (frequency == null) {
        throw new IllegalArgumentException("frequency is required");
      }
      if (leadDays < 0 || leadDays > 60) {
        throw new IllegalArgumentException("leadDays must be between 0 and 60");
      }
      if (frequency == PmFrequency.USAGE_BASED) {
        if (usageMetric == null || usageMetric.isBlank() || usageInterval == null || usageInterval.signum() <= 0) {
          throw new IllegalArgumentException("usage-based plans need usageMetric and a positive usageInterval");
        }
        anchorOn = null;
      } else {
        if (anchorOn == null) {
          throw new IllegalArgumentException("calendar plans need a start date (anchorOn)");
        }
        usageMetric = null;
        usageInterval = null;
      }
      checklistJson = checklistJson == null || checklistJson.isBlank() ? "[]" : checklistJson;
    }
  }

  public PmPlan(UUID assetId, Details d, LocalDate today) {
    super(UuidV7.next());
    this.assetId = assetId;
    this.active = true;
    apply(d, today);
  }

  public void update(Details d, LocalDate today) {
    boolean scheduleChanged = d.frequency() != frequency || !java.util.Objects.equals(d.anchorOn(), anchorOn);
    PmFrequency previous = frequency;
    LocalDate previousNext = nextDueOn;
    apply(d, today);
    if (!scheduleChanged && previous != null) {
      nextDueOn = previousNext; // keep the position in the cycle
    }
  }

  private void apply(Details d, LocalDate today) {
    this.name = d.name().trim();
    this.frequency = d.frequency();
    this.anchorOn = d.anchorOn();
    this.leadDays = d.leadDays();
    this.usageMetric = d.usageMetric() == null ? null : d.usageMetric().trim().toUpperCase(java.util.Locale.ROOT);
    this.usageInterval = d.usageInterval();
    this.checklistJson = d.checklistJson();
    this.checklistTemplateId = d.checklistTemplateId();
    this.assigneeUserId = d.assigneeUserId();
    this.vendorId = d.vendorId();
    this.nextDueOn = frequency.isCalendar() ? PmSchedule.firstOnOrAfter(frequency, anchorOn, today) : null;
  }

  /**
   * Calendar plans: the occurrence to create a task for today, advancing the plan past it.
   * Returns null when nothing is due (or the plan is inactive or usage-based).
   */
  public LocalDate takeOccurrence(LocalDate today) {
    if (!active || !frequency.isCalendar()) {
      return null;
    }
    LocalDate due = PmSchedule.occurrenceToGenerate(frequency, anchorOn, nextDueOn, leadDays, today);
    if (due != null) {
      nextDueOn = PmSchedule.nextAfter(frequency, anchorOn, due);
    }
    return due;
  }

  /** Usage plans: remembers the latest reading and says whether a PM is now due. */
  public boolean recordUsage(BigDecimal value) {
    if (latestUsage == null || value.compareTo(latestUsage) > 0) {
      latestUsage = value;
    }
    return active && frequency == PmFrequency.USAGE_BASED
        && PmSchedule.usageDue(lastDoneUsage, latestUsage, usageInterval);
  }

  /** A task of this plan was done; usage plans restart counting from the usage at that time. */
  public void taskDone(BigDecimal usageAtDone) {
    if (frequency == PmFrequency.USAGE_BASED) {
      lastDoneUsage = usageAtDone != null ? usageAtDone : latestUsage;
    }
  }

  public void deactivate() {
    this.active = false;
  }

  public void activate(LocalDate today) {
    this.active = true;
    if (frequency.isCalendar() && (nextDueOn == null || nextDueOn.isBefore(today))) {
      nextDueOn = PmSchedule.firstOnOrAfter(frequency, anchorOn, today);
    }
  }

  public UUID getAssetId() { return assetId; }
  public String getName() { return name; }
  public PmFrequency getFrequency() { return frequency; }
  public LocalDate getAnchorOn() { return anchorOn; }
  public int getLeadDays() { return leadDays; }
  public LocalDate getNextDueOn() { return nextDueOn; }
  public String getUsageMetric() { return usageMetric; }
  public BigDecimal getUsageInterval() { return usageInterval; }
  public BigDecimal getLastDoneUsage() { return lastDoneUsage; }
  public BigDecimal getLatestUsage() { return latestUsage; }
  public String getChecklistJson() { return checklistJson; }
  public UUID getChecklistTemplateId() { return checklistTemplateId; }
  public UUID getAssigneeUserId() { return assigneeUserId; }
  public UUID getVendorId() { return vendorId; }
  public boolean isActive() { return active; }
}
