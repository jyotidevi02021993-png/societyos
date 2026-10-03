package in.societyos.community.directory.application;

import in.societyos.community.directory.domain.FacilityRef;
import in.societyos.community.directory.domain.FlatRef;
import in.societyos.community.directory.domain.MembershipRef;
import in.societyos.community.directory.infrastructure.FacilityRefRepository;
import in.societyos.community.directory.infrastructure.FlatRefRepository;
import in.societyos.community.directory.infrastructure.MembershipRefRepository;
import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.core.tenant.TenantContext;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The society read model: facilities, flats (tower, label) and memberships, kept current from
 * society-service events and queried by every feature of this service.
 */
@Service
public class DirectoryService {

  private final FacilityRefRepository facilities;
  private final FlatRefRepository flats;
  private final MembershipRefRepository memberships;

  public DirectoryService(FacilityRefRepository facilities, FlatRefRepository flats,
      MembershipRefRepository memberships) {
    this.facilities = facilities;
    this.flats = flats;
    this.memberships = memberships;
  }

  // --- event handlers (run inside the listener transaction) ------------------------------

  @Transactional(propagation = Propagation.MANDATORY)
  public void facilityChanged(UUID facilityId, FacilityRef.Data data) {
    facilities.findById(facilityId).ifPresentOrElse(
        f -> f.apply(data),
        () -> facilities.save(new FacilityRef(facilityId, data)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void flatChanged(UUID flatId, UUID towerId, String label) {
    flats.findById(flatId).ifPresentOrElse(
        f -> f.apply(towerId, label),
        () -> flats.save(new FlatRef(flatId, towerId, label)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void membershipCreated(UUID membershipId, UUID flatId, UUID userId, String kind) {
    if (userId == null || flatId == null) {
      return;
    }
    memberships.findById(membershipId).ifPresentOrElse(
        MembershipRef::reactivate,
        () -> memberships.save(new MembershipRef(membershipId, flatId, userId, kind)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void membershipEnded(UUID membershipId) {
    memberships.findById(membershipId).ifPresent(MembershipRef::end);
  }

  // --- queries -----------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<FacilityRef> facilities() {
    return facilities.findBySocietyIdOrderByNameAsc(TenantContext.activeSocietyId());
  }

  @Transactional(readOnly = true)
  public FacilityRef facility(UUID id) {
    return facilities.findByIdAndSocietyId(id, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("facility", id));
  }

  /** Locks the facility row for the rest of the transaction (booking checks). */
  @Transactional(propagation = Propagation.MANDATORY)
  public FacilityRef lockFacility(UUID id) {
    return facilities.lockById(id, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("facility", id));
  }

  /** Flats in which the user has an active membership in the active society. */
  @Transactional(readOnly = true)
  public Set<UUID> flatsOf(UUID userId) {
    if (userId == null) {
      return Set.of();
    }
    return memberships.findBySocietyIdAndUserIdAndActiveTrue(TenantContext.activeSocietyId(), userId).stream()
        .map(MembershipRef::getFlatId).collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Transactional(readOnly = true)
  public boolean isMemberOf(UUID userId, UUID flatId) {
    return userId != null && flatId != null
        && memberships.existsBySocietyIdAndUserIdAndFlatIdAndActiveTrue(TenantContext.activeSocietyId(), userId, flatId);
  }

  /** Towers of the user's flats (for tower-targeted notices). */
  @Transactional(readOnly = true)
  public Set<UUID> towersOf(UUID userId) {
    Set<UUID> myFlats = flatsOf(userId);
    if (myFlats.isEmpty()) {
      return Set.of();
    }
    return flats.findBySocietyIdAndIdIn(TenantContext.activeSocietyId(), myFlats).stream()
        .map(FlatRef::getTowerId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
  }

  @Transactional(readOnly = true)
  public Map<UUID, String> flatLabels(Collection<UUID> flatIds) {
    if (flatIds.isEmpty()) {
      return Map.of();
    }
    return flats.findBySocietyIdAndIdIn(TenantContext.activeSocietyId(), Set.copyOf(flatIds)).stream()
        .collect(Collectors.toMap(FlatRef::getId, FlatRef::getLabel, (a, b) -> a));
  }

  /** Active residents of every flat in the society. */
  @Transactional(readOnly = true)
  public Set<UUID> allResidents() {
    return userIds(memberships.findBySocietyIdAndActiveTrue(TenantContext.activeSocietyId()));
  }

  /** Active residents of the flats in these towers. */
  @Transactional(readOnly = true)
  public Set<UUID> residentsOfTowers(Collection<UUID> towerIds) {
    UUID society = TenantContext.activeSocietyId();
    List<UUID> flatIds = flats.findBySocietyIdAndTowerIdIn(society, Set.copyOf(towerIds)).stream()
        .map(FlatRef::getId).toList();
    return flatIds.isEmpty() ? Set.of() : userIds(memberships.findBySocietyIdAndFlatIdInAndActiveTrue(society, flatIds));
  }

  /** Active residents of one flat. */
  @Transactional(readOnly = true)
  public Set<UUID> residentsOf(UUID flatId) {
    return userIds(memberships.findBySocietyIdAndFlatIdInAndActiveTrue(TenantContext.activeSocietyId(), List.of(flatId)));
  }

  private static Set<UUID> userIds(List<MembershipRef> list) {
    return list.stream().map(MembershipRef::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
