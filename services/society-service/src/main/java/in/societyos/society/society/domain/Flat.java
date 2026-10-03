package in.societyos.society.society.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;

/**
 * A flat (or shop) in a tower. {@code label} ("A-1203") is what guards and residents see.
 * Status follows occupancy: the first owner or tenant makes it OCCUPIED, the last one leaving
 * makes it VACANT. UNDER_RENOVATION is set by the manager and is left alone by occupancy.
 */
@Entity
@Table(name = "flat")
public class Flat extends TenantEntity {

  public static final Set<String> STATUSES = Set.of("OCCUPIED", "VACANT", "UNDER_RENOVATION");

  @Column(name = "tower_id", nullable = false)
  private UUID towerId;

  @Column(nullable = false)
  private String number;

  @Column(nullable = false)
  private String label;

  @Column(nullable = false)
  private int floor;

  @Column(name = "area_sqft")
  private Integer areaSqft;

  @Column(name = "flat_type")
  private String flatType;

  @Column(nullable = false)
  private String status;

  protected Flat() {}

  public Flat(UUID towerId, String number, String label, int floor, Integer areaSqft, String flatType) {
    super(UuidV7.next());
    this.towerId = towerId;
    this.number = number;
    this.label = label;
    this.floor = floor;
    this.areaSqft = areaSqft;
    this.flatType = flatType;
    this.status = "VACANT";
  }

  /** Manager edit. Number and tower are fixed once created: other services key on the label. */
  public void update(int floor, Integer areaSqft, String flatType, String status) {
    if (!STATUSES.contains(status)) {
      throw new IllegalArgumentException("Unknown flat status " + status);
    }
    this.floor = floor;
    this.areaSqft = areaSqft;
    this.flatType = flatType;
    this.status = status;
  }

  /** Occupancy changed; returns true when the status actually changed. */
  public boolean occupancyChanged(boolean occupied) {
    if ("UNDER_RENOVATION".equals(status)) {
      return false;
    }
    String next = occupied ? "OCCUPIED" : "VACANT";
    if (next.equals(status)) {
      return false;
    }
    status = next;
    return true;
  }

  public UUID getTowerId() { return towerId; }
  public String getNumber() { return number; }
  public String getLabel() { return label; }
  public int getFloor() { return floor; }
  public Integer getAreaSqft() { return areaSqft; }
  public String getFlatType() { return flatType; }
  public String getStatus() { return status; }
}
