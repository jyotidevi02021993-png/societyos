package in.societyos.security.directory.application;

import in.societyos.security.config.GateProperties;
import in.societyos.security.directory.application.SocietyEventData.Flat;
import in.societyos.security.directory.application.SocietyEventData.Membership;
import in.societyos.security.directory.application.SocietyEventData.RoleAssigned;
import in.societyos.security.directory.application.SocietyEventData.SettingsUpdated;
import in.societyos.security.directory.application.SocietyEventData.Vehicle;
import in.societyos.security.directory.domain.DomesticStaff;
import in.societyos.security.directory.domain.FlatDirectoryEntry;
import in.societyos.security.directory.domain.FlatResident;
import in.societyos.security.directory.domain.FlatVehicle;
import in.societyos.security.directory.domain.SocietySettings;
import in.societyos.security.directory.domain.StaffRole;
import in.societyos.security.directory.infrastructure.DomesticStaffRepository;
import in.societyos.security.directory.infrastructure.FlatDirectoryRepository;
import in.societyos.security.directory.infrastructure.FlatResidentRepository;
import in.societyos.security.directory.infrastructure.FlatVehicleRepository;
import in.societyos.security.directory.infrastructure.SocietySettingsRepository;
import in.societyos.security.directory.infrastructure.StaffRoleRepository;
import in.societyos.security.platform.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the local copies of society data (flats, residents, vehicles, domestic staff, settings)
 * and of staff role holders. Runs inside the listener transaction with the event society bound;
 * every handler is an upsert, so replays and repeated updates are harmless.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DirectoryProjection {

  private final FlatDirectoryRepository flats;
  private final FlatResidentRepository residents;
  private final FlatVehicleRepository vehicles;
  private final DomesticStaffRepository staff;
  private final SocietySettingsRepository settings;
  private final StaffRoleRepository roles;
  private final GateProperties defaults;

  public DirectoryProjection(FlatDirectoryRepository flats, FlatResidentRepository residents,
      FlatVehicleRepository vehicles, DomesticStaffRepository staff, SocietySettingsRepository settings,
      StaffRoleRepository roles, GateProperties defaults) {
    this.flats = flats;
    this.residents = residents;
    this.vehicles = vehicles;
    this.staff = staff;
    this.settings = settings;
    this.roles = roles;
    this.defaults = defaults;
  }

  public void societyCreated() {
    UUID societyId = TenantContext.activeSocietyId();
    if (!settings.existsById(societyId)) {
      settings.save(defaultsFor(societyId));
    }
  }

  public void settingsUpdated(SettingsUpdated e) {
    UUID societyId = TenantContext.activeSocietyId();
    SocietySettings s = settings.findById(societyId).orElseGet(() -> defaultsFor(societyId));
    s.update(e.intSetting("gateApprovalTimeoutSeconds"), e.intSetting("visitorRetentionDays"));
    settings.save(s);
  }

  public void flat(Flat e) {
    FlatDirectoryEntry f = flats.findById(e.flatId()).orElseGet(() -> new FlatDirectoryEntry(e.flatId()));
    f.apply(e.towerId(), e.towerName(), e.number(), e.label(), e.floor(), e.status());
    flats.save(f);
  }

  public void membershipCreated(Membership e) {
    FlatResident r = residents.findById(e.membershipId()).orElseGet(() -> new FlatResident(e.membershipId()));
    r.apply(e.flatId(), e.userId(), e.residentId(), e.residentName(), e.kind(), Boolean.TRUE.equals(e.isPrimary()));
    residents.save(r);
  }

  public void membershipEnded(Membership e, Instant at) {
    FlatResident r = residents.findById(e.membershipId()).orElseGet(() -> {
      FlatResident created = new FlatResident(e.membershipId());
      created.apply(e.flatId(), e.userId(), e.residentId(), e.residentName(), e.kind(),
          Boolean.TRUE.equals(e.isPrimary()));
      return created;
    });
    r.end(at);
    residents.save(r);
  }

  public void vehicleRegistered(Vehicle e) {
    FlatVehicle v = vehicles.findById(e.vehicleId()).orElseGet(() -> new FlatVehicle(e.vehicleId()));
    v.apply(e.flatId(), e.regNo(), e.kind(), e.rfidTag());
    vehicles.save(v);
  }

  public void vehicleRemoved(Vehicle e, Instant at) {
    vehicles.findById(e.vehicleId()).ifPresent(v -> {
      v.remove(at);
      vehicles.save(v);
    });
  }

  public void domesticStaff(SocietyEventData.DomesticStaff e) {
    DomesticStaff s = staff.findById(e.staffId()).orElseGet(() -> new DomesticStaff(e.staffId()));
    s.apply(e.name(), e.kind(), e.flatIds(), e.kycStatus(), e.photoMediaId(), e.status());
    staff.save(s);
  }

  public void roleAssigned(RoleAssigned e) {
    if (e.roleCode() == null || !StaffRole.ALERTED.contains(e.roleCode()) || roles.existsById(e.assignmentId())) {
      return;
    }
    roles.save(new StaffRole(e.assignmentId(), e.userId(), e.roleCode()));
  }

  public void roleRevoked(RoleAssigned e, Instant at) {
    roles.findById(e.assignmentId()).ifPresent(r -> {
      r.revoke(at);
      roles.save(r);
    });
  }

  private SocietySettings defaultsFor(UUID societyId) {
    return new SocietySettings(societyId, (int) defaults.approvalTimeout().toSeconds(), defaults.retentionDays());
  }
}
