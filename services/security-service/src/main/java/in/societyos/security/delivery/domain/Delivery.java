package in.societyos.security.delivery.domain;

import in.societyos.security.common.RuleViolation;
import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A parcel for a flat. Left at the gate (AT_GATE until the resident collects it) or taken up by
 * the delivery person after the resident approves the linked entry (SENT_UP).
 */
@Entity
@Table(name = "delivery")
public class Delivery extends TenantEntity {

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(nullable = false)
  private String company;

  @Column(name = "entry_id")
  private UUID entryId;

  @Column(name = "leave_at_gate", nullable = false)
  private boolean leaveAtGate;

  @Column(nullable = false)
  private String status;

  @Column(name = "gate_id")
  private UUID gateId;

  @Column(name = "received_by")
  private UUID receivedBy;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Column(name = "collected_at")
  private Instant collectedAt;

  protected Delivery() {}

  public Delivery(UUID flatId, String company, boolean leaveAtGate, UUID entryId, UUID gateId, UUID receivedBy,
      Instant at) {
    if (company == null || company.isBlank()) {
      throw new RuleViolation("COMPANY_REQUIRED", "company is required (Swiggy, Amazon, ...)");
    }
    if (!leaveAtGate && entryId == null) {
      throw new RuleViolation("ENTRY_REQUIRED", "A delivery sent up needs an entry request");
    }
    this.flatId = flatId;
    this.company = company.trim();
    this.leaveAtGate = leaveAtGate;
    this.entryId = entryId;
    this.gateId = gateId;
    this.receivedBy = receivedBy;
    this.receivedAt = at;
    this.status = leaveAtGate ? "AT_GATE" : "SENT_UP";
  }

  public void collect(Instant at) {
    if (!"AT_GATE".equals(status)) {
      throw new RuleViolation("DELIVERY_NOT_AT_GATE", "Only a parcel waiting at the gate can be collected");
    }
    status = "COLLECTED";
    collectedAt = at;
  }

  public UUID getFlatId() {
    return flatId;
  }

  public String getCompany() {
    return company;
  }

  public UUID getEntryId() {
    return entryId;
  }

  public boolean isLeaveAtGate() {
    return leaveAtGate;
  }

  public String getStatus() {
    return status;
  }

  public UUID getGateId() {
    return gateId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public Instant getCollectedAt() {
    return collectedAt;
  }
}
