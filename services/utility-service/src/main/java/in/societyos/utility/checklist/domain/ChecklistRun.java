package in.societyos.utility.checklist.domain;

import in.societyos.utility.platform.core.UuidV7;
import in.societyos.utility.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** One execution of a template on a date and shift. IN_PROGRESS → COMPLETED (final). */
@Entity
@Table(name = "checklist_run")
public class ChecklistRun extends TenantEntity {

  public static final Set<String> SHIFTS = Set.of("GENERAL", "MORNING", "EVENING", "NIGHT");

  @Column(name = "template_id", nullable = false, updatable = false)
  private UUID templateId;

  @Column(name = "template_name", nullable = false, updatable = false)
  private String templateName;

  @Column(name = "run_date", nullable = false, updatable = false)
  private LocalDate runDate;

  @Column(nullable = false, updatable = false)
  private String shift;

  @Column(name = "asset_id", updatable = false)
  private UUID assetId;

  @Column(name = "location_id", updatable = false)
  private UUID locationId;

  @Column(nullable = false)
  private String status;

  @Column(name = "started_by", updatable = false)
  private UUID startedBy;

  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  @Column(name = "completed_by")
  private UUID completedBy;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "ok_count", nullable = false)
  private int okCount;

  @Column(name = "failed_count", nullable = false)
  private int failedCount;

  @Column(name = "na_count", nullable = false)
  private int naCount;

  private String remarks;

  protected ChecklistRun() {}

  public ChecklistRun(ChecklistTemplate template, LocalDate runDate, String shift, UUID startedBy, Instant at) {
    super(UuidV7.next());
    if (!SHIFTS.contains(shift)) {
      throw new IllegalArgumentException("shift must be one of " + SHIFTS);
    }
    this.templateId = template.getId();
    this.templateName = template.getName();
    this.runDate = runDate;
    this.shift = shift;
    this.assetId = template.getAssetId();
    this.locationId = template.getLocationId();
    this.status = "IN_PROGRESS";
    this.startedBy = startedBy;
    this.startedAt = at;
  }

  public void complete(UUID userId, Instant at, int ok, int failed, int na, String remarks) {
    if (isCompleted()) {
      throw new IllegalStateException("Checklist run is already completed");
    }
    this.status = "COMPLETED";
    this.completedBy = userId;
    this.completedAt = at;
    this.okCount = ok;
    this.failedCount = failed;
    this.naCount = na;
    this.remarks = remarks;
  }

  public boolean isCompleted() { return "COMPLETED".equals(status); }

  public UUID getTemplateId() { return templateId; }
  public String getTemplateName() { return templateName; }
  public LocalDate getRunDate() { return runDate; }
  public String getShift() { return shift; }
  public UUID getAssetId() { return assetId; }
  public UUID getLocationId() { return locationId; }
  public String getStatus() { return status; }
  public UUID getStartedBy() { return startedBy; }
  public Instant getStartedAt() { return startedAt; }
  public UUID getCompletedBy() { return completedBy; }
  public Instant getCompletedAt() { return completedAt; }
  public int getOkCount() { return okCount; }
  public int getFailedCount() { return failedCount; }
  public int getNaCount() { return naCount; }
  public String getRemarks() { return remarks; }
}
