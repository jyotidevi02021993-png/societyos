package in.societyos.society.facility.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
import org.hibernate.annotations.ColumnTransformer;

/** A bookable amenity. {@code booking_rules} is the JSON form of {@link BookingRules}. */
@Entity
@Table(name = "facility")
public class Facility extends TenantEntity {

  public static final Set<String> KINDS = Set.of("CLUBHOUSE", "GYM", "POOL", "COURT", "GUEST_ROOM", "HALL", "OTHER");
  public static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

  @Column(nullable = false)
  private String kind;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private int capacity;

  @Column(name = "booking_rules", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String bookingRulesJson;

  @Column(nullable = false)
  private boolean chargeable;

  @Column(name = "charge_paise", nullable = false)
  private long chargePaise;

  @Column(nullable = false)
  private String status;

  protected Facility() {}

  public Facility(Details details, String bookingRulesJson) {
    super(UuidV7.next());
    this.status = "ACTIVE";
    update(details, bookingRulesJson);
  }

  /** Editable fields; a facility that is not chargeable always charges 0. */
  public record Details(String kind, String name, int capacity, boolean chargeable, long chargePaise, String status) {}

  public void update(Details d, String bookingRulesJson) {
    if (!KINDS.contains(d.kind())) {
      throw new IllegalArgumentException("Unknown facility kind " + d.kind());
    }
    this.kind = d.kind();
    this.name = d.name();
    this.capacity = d.capacity();
    this.chargeable = d.chargeable();
    this.chargePaise = d.chargeable() ? d.chargePaise() : 0;
    if (d.status() != null) {
      this.status = d.status();
    }
    this.bookingRulesJson = bookingRulesJson;
  }

  public String getKind() { return kind; }
  public String getName() { return name; }
  public int getCapacity() { return capacity; }
  public String getBookingRulesJson() { return bookingRulesJson; }
  public boolean isChargeable() { return chargeable; }
  public long getChargePaise() { return chargePaise; }
  public String getStatus() { return status; }
}
