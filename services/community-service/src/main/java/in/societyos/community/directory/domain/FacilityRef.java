package in.societyos.community.directory.domain;

import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;

/**
 * Local copy of a society-service facility ({@code society.facility.created/updated}); the id is
 * the society facility id. Bookings are checked against these rules without calling society-service.
 */
@Entity
@Table(name = "facility_ref")
public class FacilityRef extends TenantEntity {

  /** Kinds that many flats use at once (headcount up to capacity); every other kind is booked whole. */
  public static final Set<String> SHARED_KINDS = Set.of("GYM", "POOL");

  /** Facility data as carried by the society event. */
  public record Data(String kind, String name, int capacity, boolean chargeable, long chargePaise,
      int slotMinutes, int maxAdvanceDays, int maxPerFlatPerWeek, String status) {}

  @Column(nullable = false) private String kind;
  @Column(nullable = false) private String name;
  @Column(nullable = false) private int capacity;
  @Column(nullable = false) private boolean chargeable;
  @Column(name = "charge_paise", nullable = false) private long chargePaise;
  @Column(name = "slot_minutes", nullable = false) private int slotMinutes;
  @Column(name = "max_advance_days", nullable = false) private int maxAdvanceDays;
  @Column(name = "max_per_flat_per_week", nullable = false) private int maxPerFlatPerWeek;
  @Column(nullable = false) private String status;

  protected FacilityRef() {}

  public FacilityRef(UUID facilityId, Data d) {
    super(facilityId);
    apply(d);
  }

  public void apply(Data d) {
    this.kind = d.kind();
    this.name = d.name();
    this.capacity = d.capacity();
    this.chargeable = d.chargeable();
    this.chargePaise = d.chargePaise();
    this.slotMinutes = d.slotMinutes();
    this.maxAdvanceDays = d.maxAdvanceDays();
    this.maxPerFlatPerWeek = d.maxPerFlatPerWeek();
    this.status = d.status();
  }

  public boolean isActive() { return "ACTIVE".equals(status); }
  public boolean isExclusive() { return !SHARED_KINDS.contains(kind); }
  public String getKind() { return kind; }
  public String getName() { return name; }
  public int getCapacity() { return capacity; }
  public boolean isChargeable() { return chargeable; }
  public long getChargePaise() { return chargePaise; }
  public int getSlotMinutes() { return slotMinutes; }
  public int getMaxAdvanceDays() { return maxAdvanceDays; }
  public int getMaxPerFlatPerWeek() { return maxPerFlatPerWeek; }
  public String getStatus() { return status; }
}
