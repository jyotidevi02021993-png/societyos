package in.societyos.society.household.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A resident vehicle, matched at the gate by registration number or RFID tag. Removal is soft. */
@Entity
@Table(name = "vehicle")
public class Vehicle extends TenantEntity {

  public static final Set<String> KINDS = Set.of("CAR", "BIKE", "OTHER");

  @Column(name = "flat_id", nullable = false, updatable = false)
  private UUID flatId;

  @Column(name = "reg_no", nullable = false, updatable = false)
  private String regNo;

  @Column(nullable = false)
  private String kind;

  @Column(name = "rfid_tag")
  private String rfidTag;

  @Column(name = "removed_at")
  private Instant removedAt;

  protected Vehicle() {}

  public Vehicle(UUID flatId, String regNo, String kind, String rfidTag) {
    super(UuidV7.next());
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("Unknown vehicle kind " + kind);
    }
    this.flatId = flatId;
    this.regNo = normalizeRegNo(regNo);
    this.kind = kind;
    this.rfidTag = rfidTag == null || rfidTag.isBlank() ? null : rfidTag.trim();
  }

  /** "dl 3c-ab 1234" → "DL3CAB1234": the form the gate camera and guards compare. */
  public static String normalizeRegNo(String raw) {
    String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    if (s.length() < 4 || s.length() > 12) {
      throw new IllegalArgumentException("Registration number must have 4 to 12 letters and digits");
    }
    return s;
  }

  public void remove() {
    if (removedAt == null) {
      removedAt = Instant.now();
    }
  }

  public boolean isActive() {
    return removedAt == null;
  }

  public UUID getFlatId() { return flatId; }
  public String getRegNo() { return regNo; }
  public String getKind() { return kind; }
  public String getRfidTag() { return rfidTag; }
  public Instant getRemovedAt() { return removedAt; }
}
