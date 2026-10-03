package in.societyos.security.directory.application;

import in.societyos.security.common.Phones;
import in.societyos.security.config.GateProperties;
import in.societyos.security.directory.domain.DomesticStaff;
import in.societyos.security.directory.domain.FlatDirectoryEntry;
import in.societyos.security.directory.domain.FlatResident;
import in.societyos.security.directory.domain.FlatVehicle;
import in.societyos.security.directory.domain.StaffRole;
import in.societyos.security.directory.infrastructure.DomesticStaffRepository;
import in.societyos.security.directory.infrastructure.FlatDirectoryRepository;
import in.societyos.security.directory.infrastructure.FlatResidentRepository;
import in.societyos.security.directory.infrastructure.FlatVehicleRepository;
import in.societyos.security.directory.infrastructure.SocietySettingsRepository;
import in.societyos.security.directory.infrastructure.StaffRoleRepository;
import in.societyos.security.platform.core.Hashing;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.platform.security.FieldCrypto;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads of the local society copies, for the gate features and the guard directory screens. */
@Service
@Transactional(readOnly = true)
public class DirectoryService {

  /** Gate-relevant society settings, with the service defaults where society has none yet. */
  public record GateSettings(Duration approvalTimeout, int visitorRetentionDays) {}

  public record FlatCard(FlatDirectoryEntry flat, List<FlatResident> residents, List<FlatVehicle> vehicles,
      List<DomesticStaff> staff) {}

  public record VehicleMatch(FlatVehicle vehicle, String flatLabel, String matchedBy) {}

  private final FlatDirectoryRepository flats;
  private final FlatResidentRepository residents;
  private final FlatVehicleRepository vehicles;
  private final DomesticStaffRepository staff;
  private final SocietySettingsRepository settings;
  private final StaffRoleRepository roles;
  private final GateProperties defaults;
  private final FieldCrypto crypto;

  public DirectoryService(FlatDirectoryRepository flats, FlatResidentRepository residents,
      FlatVehicleRepository vehicles, DomesticStaffRepository staff, SocietySettingsRepository settings,
      StaffRoleRepository roles, GateProperties defaults, FieldCrypto crypto) {
    this.flats = flats;
    this.residents = residents;
    this.vehicles = vehicles;
    this.staff = staff;
    this.settings = settings;
    this.roles = roles;
    this.defaults = defaults;
    this.crypto = crypto;
  }

  private static UUID society() {
    return TenantContext.activeSocietyId();
  }

  public FlatDirectoryEntry requireFlat(UUID flatId) {
    return flats.findById(flatId).orElseThrow(() -> ProblemException.notFound("flat", flatId));
  }

  public Map<UUID, String> labels(Collection<UUID> flatIds) {
    List<UUID> ids = flatIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return flats.findByIdIn(ids).stream()
        .collect(Collectors.toMap(FlatDirectoryEntry::getId, FlatDirectoryEntry::getLabel));
  }

  /** Users to ask for approval: current members of the flat who have an app account. */
  public List<UUID> residentUserIds(UUID flatId) {
    return residents.findBySocietyIdAndFlatIdAndEndedAtIsNull(society(), flatId).stream()
        .map(FlatResident::getUserId).filter(Objects::nonNull).distinct().toList();
  }

  public List<UUID> residentUserIds(Collection<UUID> flatIds) {
    if (flatIds.isEmpty()) {
      return List.of();
    }
    return residents.findBySocietyIdAndFlatIdInAndEndedAtIsNull(society(), flatIds).stream()
        .map(FlatResident::getUserId).filter(Objects::nonNull).distinct().toList();
  }

  /** Guards and managers who receive SOS and serious incident alerts. */
  public List<UUID> alertRecipients() {
    return roles.findBySocietyIdAndRoleCodeInAndRevokedAtIsNull(society(), StaffRole.ALERTED).stream()
        .map(StaffRole::getUserId).distinct().toList();
  }

  public GateSettings settings() {
    return settings.findById(society())
        .map(s -> new GateSettings(s.approvalTimeout(), s.getVisitorRetentionDays()))
        .orElseGet(() -> new GateSettings(defaults.approvalTimeout(), defaults.retentionDays()));
  }

  public List<FlatDirectoryEntry> searchFlats(String label) {
    if (label == null || label.isBlank()) {
      throw ProblemException.badRequest("QUERY_REQUIRED", "Pass part of a flat label, e.g. A-12");
    }
    return flats.findTop20BySocietyIdAndLabelContainingIgnoreCaseOrderByLabelAsc(society(), label.trim());
  }

  public FlatCard flatCard(UUID flatId) {
    FlatDirectoryEntry flat = requireFlat(flatId);
    return new FlatCard(flat, residents.findBySocietyIdAndFlatIdAndEndedAtIsNull(society(), flatId),
        vehicles.findBySocietyIdAndFlatIdAndRemovedAtIsNull(society(), flatId), staff.findByFlat(society(), flatId));
  }

  /** Resident vehicle at the gate: by RFID tag first, then registration number. */
  public Optional<VehicleMatch> matchVehicle(String regNo, String rfidTag) {
    Optional<FlatVehicle> found = Optional.empty();
    String by = "NONE";
    if (rfidTag != null && !rfidTag.isBlank()) {
      found = vehicles.findBySocietyIdAndRfidTagAndRemovedAtIsNull(society(), rfidTag.trim()).stream().findFirst();
      by = "RFID";
    }
    if (found.isEmpty() && regNo != null && !regNo.isBlank()) {
      found = vehicles.findBySocietyIdAndRegNoAndRemovedAtIsNull(society(), FlatVehicle.normaliseRegNo(regNo))
          .stream().findFirst();
      by = "REG_NO";
    }
    String matchedBy = by;
    return found.map(v -> new VehicleMatch(v, labels(List.of(v.getFlatId())).get(v.getFlatId()), matchedBy));
  }

  public DomesticStaff requireStaff(UUID staffId) {
    return staff.findById(staffId).orElseThrow(() -> ProblemException.notFound("staff", staffId));
  }

  public Optional<DomesticStaff> staffByPhone(String phone) {
    return staff.findBySocietyIdAndPhoneHash(society(), crypto.hash(Phones.normalise(phone)));
  }

  public String maskedPhone(DomesticStaff s) {
    return s.getPhoneEnc() == null ? null : Hashing.maskPhone(crypto.decrypt(s.getPhoneEnc()));
  }

  /** Guard or manager enrols a staff member phone at the gate, so the next visit is a phone lookup. */
  @Transactional
  public DomesticStaff enrolStaffPhone(UUID staffId, String phone) {
    DomesticStaff s = requireStaff(staffId);
    String normalised = Phones.normalise(phone);
    if (normalised == null) {
      throw ProblemException.badRequest("PHONE_REQUIRED", "phone is required");
    }
    String hash = crypto.hash(normalised);
    staff.findBySocietyIdAndPhoneHash(society(), hash).filter(other -> !other.getId().equals(staffId))
        .ifPresent(other -> {
          throw ProblemException.conflict("PHONE_IN_USE", "This phone is enrolled for another staff member");
        });
    s.enrolPhone(crypto.encrypt(normalised), hash);
    return staff.save(s);
  }

  public Map<UUID, DomesticStaff> staffByIds(Collection<UUID> ids) {
    return staff.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList()).stream()
        .collect(Collectors.toMap(DomesticStaff::getId, Function.identity()));
  }
}
