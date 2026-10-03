package in.societyos.ticket.directory.application;

import in.societyos.ticket.directory.domain.AssetSummary;
import in.societyos.ticket.directory.domain.FlatMember;
import in.societyos.ticket.directory.domain.FlatRef;
import in.societyos.ticket.directory.domain.LocationRef;
import in.societyos.ticket.directory.domain.RoleHolder;
import in.societyos.ticket.directory.infrastructure.AssetSummaryRepository;
import in.societyos.ticket.directory.infrastructure.FlatMemberRepository;
import in.societyos.ticket.directory.infrastructure.FlatRefRepository;
import in.societyos.ticket.directory.infrastructure.LocationRefRepository;
import in.societyos.ticket.directory.infrastructure.RoleHolderRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local copies of other services' data (flats, residents, locations, assets, role holders), kept
 * up to date from their events. Lookups never call another service synchronously.
 */
@Service
public class Directory {

  private final FlatRefRepository flats;
  private final FlatMemberRepository members;
  private final LocationRefRepository locations;
  private final AssetSummaryRepository assets;
  private final RoleHolderRepository roles;

  public Directory(FlatRefRepository flats, FlatMemberRepository members, LocationRefRepository locations,
      AssetSummaryRepository assets, RoleHolderRepository roles) {
    this.flats = flats;
    this.members = members;
    this.locations = locations;
    this.assets = assets;
    this.roles = roles;
  }

  // --- updates from events --------------------------------------------------------------

  @Transactional
  public void upsertFlat(UUID flatId, String label, String towerName, String status) {
    FlatRef flat = flats.findById(flatId).orElseGet(() -> new FlatRef(flatId));
    flat.update(label, towerName, status);
    flats.save(flat);
  }

  @Transactional
  public void membershipCreated(UUID membershipId, UUID flatId, UUID userId, String kind) {
    if (userId == null || members.existsById(membershipId)) {
      return;
    }
    members.save(new FlatMember(membershipId, flatId, userId, kind));
  }

  @Transactional
  public void membershipEnded(UUID membershipId) {
    members.findById(membershipId).ifPresent(m -> {
      m.end();
      members.save(m);
    });
  }

  @Transactional
  public void upsertLocation(UUID locationId, String kind, String name) {
    LocationRef location = locations.findById(locationId).orElseGet(() -> new LocationRef(locationId));
    location.update(kind, name);
    locations.save(location);
  }

  @Transactional
  public void upsertAsset(UUID assetId, String code, String name, UUID locationId, String status) {
    AssetSummary asset = assets.findById(assetId).orElseGet(() -> new AssetSummary(assetId));
    String safeCode = code == null ? "?" : code;
    asset.update(safeCode, name == null ? safeCode : name, locationId, status);
    assets.save(asset);
  }

  @Transactional
  public void assetStatusChanged(UUID assetId, String code, String status) {
    Optional<AssetSummary> found = assets.findById(assetId);
    if (found.isPresent()) {
      found.get().update(null, null, null, status);
      assets.save(found.get());
    } else {
      upsertAsset(assetId, code, code, null, status);
    }
  }

  @Transactional
  public void roleAssigned(UUID assignmentId, UUID userId, String roleCode) {
    if (!roles.existsById(assignmentId)) {
      roles.save(new RoleHolder(assignmentId, userId, roleCode));
    }
  }

  @Transactional
  public void roleRevoked(UUID assignmentId) {
    roles.findById(assignmentId).ifPresent(roles::delete);
  }

  // --- lookups ------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public Optional<String> flatLabel(UUID flatId) {
    return flatId == null ? Optional.empty() : flats.findById(flatId).map(FlatRef::getLabel);
  }

  @Transactional(readOnly = true)
  public Map<UUID, String> flatLabels(Collection<UUID> flatIds) {
    Map<UUID, String> out = new HashMap<>();
    flats.findAllById(flatIds).forEach(f -> out.put(f.getId(), f.getLabel()));
    return out;
  }

  @Transactional(readOnly = true)
  public List<UUID> flatsOf(UUID userId) {
    return members.findByUserIdAndActiveTrue(userId).stream().map(FlatMember::getFlatId).distinct().toList();
  }

  @Transactional(readOnly = true)
  public List<UUID> residentsOf(UUID flatId) {
    return members.findByFlatIdAndActiveTrue(flatId).stream().map(FlatMember::getUserId).distinct().toList();
  }

  @Transactional(readOnly = true)
  public List<UUID> holdersOf(String roleCode) {
    return roles.findByRoleCode(roleCode).stream().map(RoleHolder::getUserId).distinct().toList();
  }

  @Transactional(readOnly = true)
  public Optional<AssetSummary> asset(UUID assetId) {
    return assetId == null ? Optional.empty() : assets.findById(assetId);
  }

  @Transactional(readOnly = true)
  public Optional<String> locationName(UUID locationId) {
    return locationId == null ? Optional.empty() : locations.findById(locationId).map(LocationRef::getName);
  }
}
