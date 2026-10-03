package in.societyos.audit.log.domain;

import in.societyos.audit.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A record that someone exported part of the audit trail (who = created_by, when = created_at). */
@Entity
@Table(name = "audit_export")
public class AuditExport extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String format;

  @Column(nullable = false, updatable = false)
  private String filters;

  @Column(name = "row_count", nullable = false, updatable = false)
  private int rowCount;

  protected AuditExport() {}

  public AuditExport(String format, String filters, int rowCount) {
    this.format = format;
    this.filters = filters;
    this.rowCount = rowCount;
  }

  public String getFormat() {
    return format;
  }

  public String getFilters() {
    return filters;
  }

  public int getRowCount() {
    return rowCount;
  }
}
