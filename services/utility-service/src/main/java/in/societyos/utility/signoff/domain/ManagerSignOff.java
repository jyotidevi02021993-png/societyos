package in.societyos.utility.signoff.domain;

import in.societyos.utility.platform.core.UuidV7;
import in.societyos.utility.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** The manager's daily sign-off, with a snapshot of the day's summary. One per society per date. */
@Entity
@Table(name = "manager_signoff")
public class ManagerSignOff extends TenantEntity {

  @Column(name = "sign_date", nullable = false, updatable = false)
  private LocalDate signDate;

  @Column(name = "manager_user_id", nullable = false, updatable = false)
  private UUID managerUserId;

  @Column(updatable = false)
  private String remarks;

  @Column(name = "summary", nullable = false, updatable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String summaryJson;

  @Column(name = "signed_at", nullable = false, updatable = false)
  private Instant signedAt;

  protected ManagerSignOff() {}

  public ManagerSignOff(LocalDate signDate, UUID managerUserId, String remarks, String summaryJson, Instant signedAt) {
    super(UuidV7.next());
    this.signDate = signDate;
    this.managerUserId = managerUserId;
    this.remarks = remarks;
    this.summaryJson = summaryJson;
    this.signedAt = signedAt;
  }

  public LocalDate getSignDate() { return signDate; }
  public UUID getManagerUserId() { return managerUserId; }
  public String getRemarks() { return remarks; }
  public String getSummaryJson() { return summaryJson; }
  public Instant getSignedAt() { return signedAt; }
}
