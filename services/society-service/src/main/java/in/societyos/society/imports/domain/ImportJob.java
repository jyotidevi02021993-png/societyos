package in.societyos.society.imports.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnTransformer;

/** One Excel upload: PENDING → RUNNING → a final status, with the report as JSON. */
@Entity
@Table(name = "import_job")
public class ImportJob extends TenantEntity {

  public enum Status { PENDING, RUNNING, VALIDATION_FAILED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED }

  @Column(name = "file_name", nullable = false, updatable = false)
  private String fileName;

  @Column(name = "dry_run", nullable = false, updatable = false)
  private boolean dryRun;

  @Column(nullable = false)
  private String status;

  @Column(name = "report", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String reportJson;

  @Column(name = "finished_at")
  private Instant finishedAt;

  protected ImportJob() {}

  public ImportJob(String fileName, boolean dryRun) {
    super(UuidV7.next());
    this.fileName = fileName;
    this.dryRun = dryRun;
    this.status = Status.PENDING.name();
  }

  public void start() {
    this.status = Status.RUNNING.name();
  }

  public void finish(Status status, String reportJson) {
    this.status = status.name();
    this.reportJson = reportJson;
    this.finishedAt = Instant.now();
  }

  public boolean isOpen() {
    return finishedAt == null;
  }

  public String getFileName() { return fileName; }
  public boolean isDryRun() { return dryRun; }
  public Status getStatus() { return Status.valueOf(status); }
  public String getReportJson() { return reportJson; }
  public Instant getFinishedAt() { return finishedAt; }
}
