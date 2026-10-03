package in.societyos.security.vehicle.domain;

import in.societyos.security.common.RuleViolation;
import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A resident vehicle (or an unknown one) passing the gate, matched against the society copy. */
@Entity
@Table(name = "vehicle_movement")
public class VehicleMovement extends TenantEntity {

  public static final Set<String> DIRECTIONS = Set.of("IN", "OUT");

  @Column(name = "vehicle_id")
  private UUID vehicleId;

  @Column(name = "flat_id")
  private UUID flatId;

  @Column(name = "reg_no", nullable = false)
  private String regNo;

  @Column(nullable = false)
  private String direction;

  @Column(name = "matched_by", nullable = false)
  private String matchedBy;

  @Column(name = "gate_id")
  private UUID gateId;

  @Column(name = "guard_id")
  private UUID guardId;

  @Column(nullable = false)
  private Instant at;

  protected VehicleMovement() {}

  public VehicleMovement(UUID vehicleId, UUID flatId, String regNo, String direction, String matchedBy, UUID gateId,
      UUID guardId, Instant at) {
    if (!DIRECTIONS.contains(direction)) {
      throw new RuleViolation("INVALID_DIRECTION", "direction must be IN or OUT");
    }
    if (regNo == null || regNo.isBlank()) {
      throw new RuleViolation("REG_NO_REQUIRED", "regNo is required when the vehicle is not recognised");
    }
    this.vehicleId = vehicleId;
    this.flatId = flatId;
    this.regNo = regNo;
    this.direction = direction;
    this.matchedBy = matchedBy;
    this.gateId = gateId;
    this.guardId = guardId;
    this.at = at;
  }

  public boolean isKnown() {
    return vehicleId != null;
  }

  public UUID getVehicleId() {
    return vehicleId;
  }

  public UUID getFlatId() {
    return flatId;
  }

  public String getRegNo() {
    return regNo;
  }

  public String getDirection() {
    return direction;
  }

  public String getMatchedBy() {
    return matchedBy;
  }

  public UUID getGateId() {
    return gateId;
  }

  public Instant getAt() {
    return at;
  }
}
