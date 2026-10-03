package in.societyos.society.household.application;

import in.societyos.society.common.Phones;
import in.societyos.society.household.domain.DomesticStaff;
import in.societyos.society.household.domain.HouseholdEvents;
import in.societyos.society.household.infrastructure.DomesticStaffRepository;
import in.societyos.society.member.application.HouseholdAccess;
import in.societyos.society.platform.core.Hashing;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.events.DomainEvents;
import in.societyos.society.platform.security.FieldCrypto;
import in.societyos.society.society.application.FlatService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DomesticStaffService {

  /** A staff member as screens see them: the phone only ever masked. */
  public record StaffView(DomesticStaff staff, String phoneMasked) {}

  public record NewStaff(String name, String kind, String phone, UUID photoMediaId, Set<UUID> flatIds) {}

  public record StaffChange(String name, String kind, UUID photoMediaId, String kycStatus, String status) {}

  private final DomesticStaffRepository staff;
  private final FlatService flats;
  private final HouseholdAccess access;
  private final FieldCrypto crypto;
  private final DomainEvents events;

  public DomesticStaffService(DomesticStaffRepository staff, FlatService flats, HouseholdAccess access,
      FieldCrypto crypto, DomainEvents events) {
    this.staff = staff;
    this.flats = flats;
    this.access = access;
    this.crypto = crypto;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<StaffView> list(UUID flatId) {
    if (flatId == null) {
      access.requireReadAll();
      return staff.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }
    access.requireFlatRead(flatId);
    return staff.findByFlat(flatId).stream().map(this::view).toList();
  }

  /** Gate lookup: the guard types the number the staff member gives. */
  @Transactional(readOnly = true)
  public Optional<StaffView> lookupByPhone(String phone) {
    access.requireReadAll();
    return staff.findByPhoneHash(crypto.hash(Phones.normalize(phone))).map(this::view);
  }

  /**
   * Registers a staff member for flats, or links an already-known person (same phone) to more
   * flats: one person is one record however many homes they work in.
   */
  @Transactional
  public StaffView register(NewStaff n) {
    if (n.flatIds() == null || n.flatIds().isEmpty()) {
      throw ProblemException.badRequest("FLAT_REQUIRED", "Give at least one flat");
    }
    access.requireFlats(n.flatIds());
    n.flatIds().forEach(flats::require);
    String kind = checkedKind(n.kind());
    String phone = Phones.normalize(n.phone());
    String hash = crypto.hash(phone);

    Optional<DomesticStaff> existing = staff.findByPhoneHash(hash);
    if (existing.isPresent()) {
      DomesticStaff s = existing.get();
      boolean changed = false;
      for (UUID flatId : n.flatIds()) {
        changed |= s.addFlat(flatId);
      }
      if (changed) {
        events.publish(HouseholdEvents.updated(staff.save(s)));
      }
      return view(s);
    }
    DomesticStaff s = new DomesticStaff(n.name().trim(), kind, crypto.encrypt(phone), hash, n.photoMediaId());
    n.flatIds().forEach(s::addFlat);
    DomesticStaff saved = staff.save(s);
    events.publish(HouseholdEvents.registered(saved));
    return view(saved);
  }

  /** Residents may fix name, kind and photo of their own staff; KYC and blocking are for managers. */
  @Transactional
  public StaffView update(UUID id, StaffChange c) {
    DomesticStaff s = require(id);
    boolean manager = access.isManager();
    if (!manager) {
      requireServesOwnFlat(s);
      if (c.kycStatus() != null || c.status() != null) {
        throw ProblemException.forbidden("MANAGER_ONLY", "Only a manager can change KYC or block staff");
      }
    }
    s.update(c.name().trim(), checkedKind(c.kind()), c.photoMediaId());
    try {
      if (c.kycStatus() != null) {
        s.setKycStatus(c.kycStatus().trim());
      }
      if (c.status() != null) {
        s.setStatus(c.status().trim());
      }
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_STAFF_STATUS", e.getMessage());
    }
    DomesticStaff saved = staff.save(s);
    events.publish(HouseholdEvents.updated(saved));
    return view(saved);
  }

  @Transactional
  public StaffView addFlat(UUID id, UUID flatId) {
    access.requireFlat(flatId);
    flats.require(flatId);
    DomesticStaff s = require(id);
    if (s.addFlat(flatId)) {
      events.publish(HouseholdEvents.updated(staff.save(s)));
    }
    return view(s);
  }

  /** Unlinks a flat. The record stays even with no flats, so gate history keeps its name. */
  @Transactional
  public StaffView removeFlat(UUID id, UUID flatId) {
    access.requireFlat(flatId);
    DomesticStaff s = require(id);
    if (s.removeFlat(flatId)) {
      events.publish(HouseholdEvents.updated(staff.save(s)));
    }
    return view(s);
  }

  private void requireServesOwnFlat(DomesticStaff s) {
    for (UUID flatId : s.getFlatIds()) {
      try {
        access.requireFlat(flatId);
        return;
      } catch (ProblemException notThisOne) {
        // try the next flat
      }
    }
    throw ProblemException.forbidden("NOT_YOUR_STAFF", "This person does not work for your flat");
  }

  private DomesticStaff require(UUID id) {
    return staff.findById(id).orElseThrow(() -> ProblemException.notFound("domestic_staff", id));
  }

  private static String checkedKind(String kind) {
    String k = kind == null ? "" : kind.trim().toUpperCase();
    if (!DomesticStaff.KINDS.contains(k)) {
      throw ProblemException.badRequest("INVALID_STAFF_KIND", "kind must be one of " + DomesticStaff.KINDS);
    }
    return k;
  }

  private StaffView view(DomesticStaff s) {
    return new StaffView(s, Hashing.maskPhone(crypto.decrypt(s.getPhoneEnc())));
  }
}
