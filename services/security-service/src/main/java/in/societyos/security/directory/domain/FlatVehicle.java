package in.societyos.security.directory.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** A resident vehicle (from {@code society.vehicle.registered/removed}); id = vehicle id. */
@Entity
@Table(name = "flat_vehicle")
public class FlatVehicle extends TenantEntity {

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(name = "reg_no", nullable = false)
  private String regNo;

  @Column(nullable = false)
  private String kind;

  @Column(name = "rfid_tag")
  private String rfidTag;

  @Column(name = "removed_at")
  private Instant removedAt;

  protected FlatVehicle() {}

  public FlatVehicle(UUID vehicleId) {
    super(vehicleId);
  }

  /** Registration numbers are compared without spaces or dashes, upper case: "KA 01-AB 1234" = "KA01AB1234". */
  public static String normaliseRegNo(String regNo) {
    return regNo == null ? null : regNo.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
  }

  public void apply(UUID flatId, String regNo, String kind, String rfidTag) {
    this.flatId = flatId;
    this.regNo = normaliseRegNo(regNo);
    this.kind = kind == null ? "OTHER" : kind;
    this.rfidTag = rfidTag;
    this.removedAt = null;
  }

  public void remove(Instant at) {
    if (removedAt == null) {
      removedAt = at;
    }
  }

  public UUID getFlatId() {
    return flatId;
  }

  public String getRegNo() {
    return regNo;
  }

  public String getKind() {
    return kind;
  }

  public String getRfidTag() {
    return rfidTag;
  }

  public boolean isActive() {
    return removedAt == null;
  }
}
