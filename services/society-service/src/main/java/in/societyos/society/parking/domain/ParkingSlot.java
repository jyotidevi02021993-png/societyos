package in.societyos.society.parking.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;

/** A numbered parking slot, optionally allotted to one flat. VISITOR slots are never allotted. */
@Entity
@Table(name = "parking_slot")
public class ParkingSlot extends TenantEntity {

  public static final Set<String> KINDS = Set.of("COVERED", "OPEN", "BASEMENT", "VISITOR");

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String kind;

  @Column(name = "flat_id")
  private UUID flatId;

  protected ParkingSlot() {}

  public ParkingSlot(String code, String kind) {
    super(UuidV7.next());
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("Unknown parking kind " + kind);
    }
    this.code = code;
    this.kind = kind;
  }

  /** Allots the slot to a flat, or frees it with {@code null}. */
  public void assign(UUID flatId) {
    if (flatId != null && "VISITOR".equals(kind)) {
      throw new IllegalStateException("Visitor slots cannot be allotted");
    }
    this.flatId = flatId;
  }

  public String getCode() { return code; }
  public String getKind() { return kind; }
  public UUID getFlatId() { return flatId; }
}
