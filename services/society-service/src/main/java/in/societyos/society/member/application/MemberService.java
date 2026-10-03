package in.societyos.society.member.application;

import in.societyos.society.common.Phones;
import in.societyos.society.member.domain.FlatMembership;
import in.societyos.society.member.domain.MembershipEvents;
import in.societyos.society.member.domain.Resident;
import in.societyos.society.member.infrastructure.FlatMembershipRepository;
import in.societyos.society.member.infrastructure.ResidentRepository;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.core.tenant.TenantContext;
import in.societyos.society.platform.events.DomainEvents;
import in.societyos.society.society.application.FlatService;
import in.societyos.society.society.application.SocietyService;
import in.societyos.society.society.domain.Flat;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Residents and their flat memberships. */
@Service
public class MemberService {

  /** A membership with the names a screen needs. */
  public record MemberView(FlatMembership membership, String flatLabel, String residentName) {}

  public record NewMember(UUID flatId, String phone, String name, String kind, LocalDate fromDate, boolean primary) {}

  private final ResidentRepository residents;
  private final FlatMembershipRepository memberships;
  private final FlatService flats;
  private final SocietyService societies;
  private final UserDirectory users;
  private final DomainEvents events;
  private final TransactionTemplate tx;

  public MemberService(ResidentRepository residents, FlatMembershipRepository memberships, FlatService flats,
      SocietyService societies, UserDirectory users, DomainEvents events, PlatformTransactionManager txManager) {
    this.residents = residents;
    this.memberships = memberships;
    this.flats = flats;
    this.societies = societies;
    this.users = users;
    this.events = events;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * Adds a person to a flat. identity-service is called first, outside the transaction, so no
   * database connection is held during the remote call.
   */
  public MemberView add(NewMember m) {
    String kind = m.kind() == null ? "" : m.kind().trim().toUpperCase();
    if (!FlatMembership.KINDS.contains(kind)) {
      throw ProblemException.badRequest("INVALID_MEMBERSHIP_KIND", "kind must be OWNER, TENANT or FAMILY");
    }
    if (m.primary() && !FlatMembership.HOLDER_KINDS.contains(kind)) {
      throw ProblemException.badRequest("INVALID_PRIMARY", "Only an owner or tenant can be primary");
    }
    String phone = Phones.normalize(m.phone());
    String name = m.name().trim();
    flats.require(m.flatId());
    UUID userId = users.resolveByPhone(phone, name);
    return tx.execute(status -> addForUser(m.flatId(), userId, name, kind, m.fromDate(), m.primary()));
  }

  private MemberView addForUser(UUID flatId, UUID userId, String name, String kind, LocalDate fromDate, boolean primary) {
    Flat flat = flats.require(flatId);
    Resident resident = residents.findByUserId(userId).orElseGet(() -> new Resident(userId, name));
    resident.rename(name);
    resident = residents.save(resident);

    if (memberships.existsByFlatIdAndResidentIdAndEndedAtIsNull(flatId, resident.getId())) {
      throw ProblemException.conflict("MEMBERSHIP_EXISTS", "This person is already a member of " + flat.getLabel());
    }
    if (primary && memberships.existsByFlatIdAndKindAndPrimaryTrueAndEndedAtIsNull(flatId, kind)) {
      throw ProblemException.conflict("PRIMARY_EXISTS", flat.getLabel() + " already has a primary " + kind.toLowerCase());
    }
    boolean hadHolder = memberships.existsByFlatIdAndKindInAndEndedAtIsNull(flatId, FlatMembership.HOLDER_KINDS);
    if ("FAMILY".equals(kind) && !hadHolder) {
      throw ProblemException.unprocessable("NO_FLAT_HOLDER", "Add the owner or tenant before family members");
    }
    FlatMembership membership = memberships.save(new FlatMembership(flatId, resident, kind,
        fromDate != null ? fromDate : societies.today(), primary));
    events.publish(MembershipEvents.created(membership, resident));
    if (membership.isHolder() && !hadHolder) {
      flats.occupancyChanged(flatId, true);
    }
    return new MemberView(membership, flat.getLabel(), resident.getName());
  }

  @Transactional
  public MemberView end(UUID membershipId, LocalDate toDate) {
    FlatMembership membership = memberships.findById(membershipId)
        .orElseThrow(() -> ProblemException.notFound("membership", membershipId));
    if (!membership.isActive()) {
      throw ProblemException.unprocessable("MEMBERSHIP_ENDED", "This membership has already ended");
    }
    try {
      membership.end(toDate != null ? toDate : societies.today());
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_END_DATE", e.getMessage());
    }
    memberships.save(membership);
    Resident resident = residents.findById(membership.getResidentId()).orElseThrow();
    events.publish(MembershipEvents.ended(membership, resident));
    if (membership.isHolder()
        && !memberships.existsByFlatIdAndKindInAndEndedAtIsNull(membership.getFlatId(), FlatMembership.HOLDER_KINDS)) {
      flats.occupancyChanged(membership.getFlatId(), false);
    }
    return new MemberView(membership, flats.require(membership.getFlatId()).getLabel(), resident.getName());
  }

  @Transactional(readOnly = true)
  public List<MemberView> listByFlat(UUID flatId, boolean includeEnded) {
    Flat flat = flats.require(flatId);
    List<FlatMembership> found = includeEnded
        ? memberships.findByFlatIdOrderByFromDateAsc(flatId)
        : memberships.findByFlatIdAndEndedAtIsNullOrderByFromDateAsc(flatId);
    Map<UUID, Resident> byId = residentsById(found.stream().map(FlatMembership::getResidentId).toList());
    return found.stream()
        .map(m -> new MemberView(m, flat.getLabel(), nameOf(byId, m.getResidentId())))
        .toList();
  }

  /** Every membership in the active society (row-level security scopes the query), by flat. */
  @Transactional(readOnly = true)
  public List<MemberView> listAll(boolean includeEnded) {
    List<FlatMembership> found = includeEnded
        ? memberships.findAllByOrderByFromDateAsc()
        : memberships.findByEndedAtIsNullOrderByFromDateAsc();
    Map<UUID, Flat> flatsById = flats.byIds(found.stream().map(FlatMembership::getFlatId).distinct().toList());
    Map<UUID, Resident> byId = residentsById(found.stream().map(FlatMembership::getResidentId).toList());
    return found.stream()
        .map(m -> new MemberView(m, labelOf(flatsById, m.getFlatId()), nameOf(byId, m.getResidentId())))
        .sorted(java.util.Comparator.comparing(MemberView::flatLabel))
        .toList();
  }

  /** The caller's active memberships in the active society. */
  @Transactional(readOnly = true)
  public List<MemberView> myFlats() {
    UUID userId = TenantContext.userId()
        .orElseThrow(() -> ProblemException.forbidden("USER_REQUIRED", "Only a signed-in person has flats"));
    List<FlatMembership> found = memberships.findByUserIdAndEndedAtIsNull(userId);
    Map<UUID, Flat> flatsById = flats.byIds(found.stream().map(FlatMembership::getFlatId).distinct().toList());
    Map<UUID, Resident> byId = residentsById(found.stream().map(FlatMembership::getResidentId).toList());
    return found.stream()
        .map(m -> new MemberView(m, labelOf(flatsById, m.getFlatId()), nameOf(byId, m.getResidentId())))
        .sorted(java.util.Comparator.comparing(MemberView::flatLabel))
        .toList();
  }

  /** True when the user is an active owner or tenant of the flat. */
  @Transactional(readOnly = true)
  public boolean isHolderOf(UUID userId, UUID flatId) {
    return memberships.existsByFlatIdAndUserIdAndKindInAndEndedAtIsNull(flatId, userId, FlatMembership.HOLDER_KINDS);
  }

  @Transactional
  public Resident setDirectoryOptIn(boolean optIn) {
    UUID userId = TenantContext.userId()
        .orElseThrow(() -> ProblemException.forbidden("USER_REQUIRED", "Only a signed-in person has a profile"));
    Resident resident = residents.findByUserId(userId)
        .orElseThrow(() -> ProblemException.notFound("resident", userId));
    resident.setDirectoryOptIn(optIn);
    return residents.save(resident);
  }

  /** Opted-in residents with at least one active membership, with their flats. */
  public record DirectoryEntry(UUID residentId, String name, List<DirectoryFlat> flats) {}

  public record DirectoryFlat(UUID flatId, UUID towerId, String flatLabel, String kind) {}

  @Transactional(readOnly = true)
  public List<DirectoryEntry> directory(UUID towerId) {
    if (Boolean.FALSE.equals(societies.settings().directoryEnabled())) {
      throw ProblemException.forbidden("DIRECTORY_DISABLED", "This society has turned the directory off");
    }
    List<Resident> optedIn = residents.findByDirectoryOptInTrueOrderByNameAsc();
    if (optedIn.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<FlatMembership>> byResident = memberships
        .findByResidentIdInAndEndedAtIsNull(optedIn.stream().map(Resident::getId).toList()).stream()
        .collect(Collectors.groupingBy(FlatMembership::getResidentId));
    Map<UUID, Flat> flatsById = flats.byIds(byResident.values().stream().flatMap(List::stream)
        .map(FlatMembership::getFlatId).distinct().toList());
    return optedIn.stream()
        .map(r -> new DirectoryEntry(r.getId(), r.getName(), byResident.getOrDefault(r.getId(), List.of()).stream()
            .map(m -> flatsById.get(m.getFlatId()))
            .filter(f -> f != null && (towerId == null || towerId.equals(f.getTowerId())))
            .map(f -> new DirectoryFlat(f.getId(), f.getTowerId(), f.getLabel(),
                kindIn(byResident.get(r.getId()), f.getId())))
            .toList()))
        .filter(e -> !e.flats().isEmpty())
        .toList();
  }

  private static String kindIn(List<FlatMembership> ms, UUID flatId) {
    return ms.stream().filter(m -> m.getFlatId().equals(flatId)).map(FlatMembership::getKind).findFirst().orElse(null);
  }

  private Map<UUID, Resident> residentsById(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return residents.findByIdIn(ids).stream().collect(Collectors.toMap(Resident::getId, Function.identity()));
  }

  private static String nameOf(Map<UUID, Resident> byId, UUID id) {
    Resident r = byId.get(id);
    return r == null ? null : r.getName();
  }

  private static String labelOf(Map<UUID, Flat> byId, UUID id) {
    Flat f = byId.get(id);
    return f == null ? "" : f.getLabel();
  }
}
