package in.societyos.society.household.domain;

import in.societyos.society.common.SocietyEvent;
import java.util.List;
import java.util.UUID;

/** Vehicle and domestic staff events: security-service keeps the gate's copy from these. No phones. */
public final class HouseholdEvents {

  private HouseholdEvents() {}

  public record VehicleRegistered(UUID vehicleId, UUID flatId, String regNo, String kind, String rfidTag)
      implements SocietyEvent {
    @Override public String type() { return "society.vehicle.registered"; }
    @Override public UUID aggregateId() { return vehicleId; }
  }

  public record VehicleRemoved(UUID vehicleId, UUID flatId, String regNo, String kind, String rfidTag)
      implements SocietyEvent {
    @Override public String type() { return "society.vehicle.removed"; }
    @Override public UUID aggregateId() { return vehicleId; }
  }

  public record DomesticStaffRegistered(UUID staffId, String name, String kind, List<UUID> flatIds, String kycStatus,
      UUID photoMediaId, String status) implements SocietyEvent {
    @Override public String type() { return "society.domesticstaff.registered"; }
    @Override public UUID aggregateId() { return staffId; }
  }

  public record DomesticStaffUpdated(UUID staffId, String name, String kind, List<UUID> flatIds, String kycStatus,
      UUID photoMediaId, String status) implements SocietyEvent {
    @Override public String type() { return "society.domesticstaff.updated"; }
    @Override public UUID aggregateId() { return staffId; }
  }

  public static VehicleRegistered registered(Vehicle v) {
    return new VehicleRegistered(v.getId(), v.getFlatId(), v.getRegNo(), v.getKind(), v.getRfidTag());
  }

  public static VehicleRemoved removed(Vehicle v) {
    return new VehicleRemoved(v.getId(), v.getFlatId(), v.getRegNo(), v.getKind(), v.getRfidTag());
  }

  public static DomesticStaffRegistered registered(DomesticStaff s) {
    return new DomesticStaffRegistered(s.getId(), s.getName(), s.getKind(), List.copyOf(s.getFlatIds()),
        s.getKycStatus(), s.getPhotoMediaId(), s.getStatus());
  }

  public static DomesticStaffUpdated updated(DomesticStaff s) {
    return new DomesticStaffUpdated(s.getId(), s.getName(), s.getKind(), List.copyOf(s.getFlatIds()),
        s.getKycStatus(), s.getPhotoMediaId(), s.getStatus());
  }
}
