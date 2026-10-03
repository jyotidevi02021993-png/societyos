package in.societyos.society.household.application;

import in.societyos.society.household.domain.HouseholdEvents;
import in.societyos.society.household.domain.Vehicle;
import in.societyos.society.household.infrastructure.VehicleRepository;
import in.societyos.society.member.application.HouseholdAccess;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.events.DomainEvents;
import in.societyos.society.society.application.FlatService;
import in.societyos.society.society.domain.Flat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleService {

  public record VehicleView(Vehicle vehicle, String flatLabel) {}

  private final VehicleRepository vehicles;
  private final FlatService flats;
  private final HouseholdAccess access;
  private final DomainEvents events;

  public VehicleService(VehicleRepository vehicles, FlatService flats, HouseholdAccess access, DomainEvents events) {
    this.vehicles = vehicles;
    this.flats = flats;
    this.access = access;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<VehicleView> list(UUID flatId) {
    List<Vehicle> found;
    if (flatId == null) {
      access.requireReadAll();
      found = vehicles.findByRemovedAtIsNullOrderByRegNoAsc();
    } else {
      access.requireFlatRead(flatId);
      found = vehicles.findByFlatIdAndRemovedAtIsNullOrderByRegNoAsc(flatId);
    }
    Map<UUID, Flat> byId = flats.byIds(found.stream().map(Vehicle::getFlatId).distinct().toList());
    return found.stream().map(v -> view(v, byId.get(v.getFlatId()))).toList();
  }

  /** Gate lookup by registration number or RFID tag. */
  @Transactional(readOnly = true)
  public Optional<VehicleView> lookup(String regNo, String rfidTag) {
    access.requireReadAll();
    Optional<Vehicle> found;
    if (rfidTag != null && !rfidTag.isBlank()) {
      found = vehicles.findByRfidTagAndRemovedAtIsNull(rfidTag.trim());
    } else if (regNo != null && !regNo.isBlank()) {
      found = vehicles.findByRegNoAndRemovedAtIsNull(normalized(regNo));
    } else {
      throw ProblemException.badRequest("LOOKUP_KEY_REQUIRED", "Pass regNo or rfidTag");
    }
    return found.map(v -> view(v, flats.require(v.getFlatId())));
  }

  @Transactional
  public VehicleView register(UUID flatId, String regNo, String kind, String rfidTag) {
    access.requireFlat(flatId);
    Flat flat = flats.require(flatId);
    if (!Vehicle.KINDS.contains(kind)) {
      throw ProblemException.badRequest("INVALID_VEHICLE_KIND", "kind must be one of " + Vehicle.KINDS);
    }
    String reg = normalized(regNo);
    if (vehicles.findByRegNoAndRemovedAtIsNull(reg).isPresent()) {
      throw ProblemException.conflict("VEHICLE_EXISTS", "A vehicle with this registration is already registered");
    }
    if (rfidTag != null && !rfidTag.isBlank() && vehicles.findByRfidTagAndRemovedAtIsNull(rfidTag.trim()).isPresent()) {
      throw ProblemException.conflict("RFID_TAG_IN_USE", "This RFID tag is already on another vehicle");
    }
    Vehicle saved = vehicles.save(new Vehicle(flatId, reg, kind, rfidTag));
    events.publish(HouseholdEvents.registered(saved));
    return view(saved, flat);
  }

  @Transactional
  public void remove(UUID id) {
    Vehicle vehicle = vehicles.findById(id).filter(Vehicle::isActive)
        .orElseThrow(() -> ProblemException.notFound("vehicle", id));
    access.requireFlat(vehicle.getFlatId());
    vehicle.remove();
    vehicles.save(vehicle);
    events.publish(HouseholdEvents.removed(vehicle));
  }

  private static String normalized(String regNo) {
    try {
      return Vehicle.normalizeRegNo(regNo);
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_REG_NO", e.getMessage());
    }
  }

  private static VehicleView view(Vehicle v, Flat flat) {
    return new VehicleView(v, flat == null ? null : flat.getLabel());
  }
}
