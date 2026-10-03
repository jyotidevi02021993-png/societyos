package in.societyos.security.entry.application;

import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.directory.application.GateAccess;
import in.societyos.security.directory.domain.FlatDirectoryEntry;
import in.societyos.security.entry.domain.EntryEvents;
import in.societyos.security.entry.domain.EntryLog;
import in.societyos.security.entry.domain.EntryLog.Arrival;
import in.societyos.security.entry.domain.EntryLog.Status;
import in.societyos.security.entry.infrastructure.EntryLogRepository;
import in.societyos.security.entry.infrastructure.PendingApprovalCache;
import in.societyos.security.gatepass.application.GatePassService;
import in.societyos.security.gatepass.domain.GatePass;
import in.societyos.security.notification.application.Notifier;
import in.societyos.security.notification.domain.NotificationRequested;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.platform.events.DomainEvents;
import in.societyos.security.platform.web.CursorPage;
import in.societyos.security.visitor.application.VisitorService;
import in.societyos.security.visitor.application.VisitorService.VisitorView;
import in.societyos.security.visitor.domain.Visitor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The gate flow (docs/architecture/06 §1): guard raises a walk-in request, residents approve or
 * deny inside the society timeout, the guard checks the visitor in and out. Pre-approved passes
 * skip the prompt. Every state change publishes its catalogue event in the same transaction.
 */
@Service
public class EntryService {

  public record NewEntry(UUID flatId, UUID gateId, String visitorName, String visitorPhone, UUID photoMediaId,
      String purpose, String company, String vehicleReg) {}

  public record PassEntry(String code, String qrToken, UUID gateId, String visitorName, String visitorPhone,
      UUID photoMediaId, String vehicleReg) {}

  public record EdgeEntry(String clientEntryId, String passCode, String qrToken, UUID flatId, UUID gateId,
      String visitorName, String purpose, String vehicleReg, Instant inAt, Instant outAt) {}

  public record EdgeResult(String clientEntryId, UUID entryId, String result, List<String> warnings) {}

  public record Filter(UUID flatId, String status, Instant from, Instant to, String cursor, Integer limit) {}

  /** An entry with the visitor as the caller may see it (phone masked). */
  public record EntryView(EntryLog entry, VisitorView visitor) {}

  private final EntryLogRepository entries;
  private final VisitorService visitors;
  private final GatePassService passes;
  private final DirectoryService directory;
  private final GateAccess access;
  private final DomainEvents events;
  private final Notifier notifier;
  private final PendingApprovalCache pending;
  private final Clock clock;

  public EntryService(EntryLogRepository entries, VisitorService visitors, GatePassService passes,
      DirectoryService directory, GateAccess access, DomainEvents events, Notifier notifier,
      PendingApprovalCache pending, Clock clock) {
    this.entries = entries;
    this.visitors = visitors;
    this.passes = passes;
    this.directory = directory;
    this.access = access;
    this.events = events;
    this.notifier = notifier;
    this.pending = pending;
    this.clock = clock;
  }

  /** Walk-in, delivery, cab or service visit that needs a resident decision. */
  @Transactional
  public EntryView request(NewEntry cmd) {
    Instant now = clock.instant();
    FlatDirectoryEntry flat = directory.requireFlat(cmd.flatId());
    Visitor visitor = visitors.record(cmd.visitorName(), cmd.visitorPhone(), cmd.photoMediaId(), now);
    List<UUID> residents = directory.residentUserIds(flat.getId());
    EntryLog entry = entries.save(EntryLog.request(
        new Arrival(flat.getId(), flat.getLabel(), cmd.gateId(), visitor.getId(), visitor.getName(),
            upper(cmd.purpose()), cmd.company(), cmd.vehicleReg(), cmd.photoMediaId(), access.userId()),
        now, directory.settings().approvalTimeout()));
    events.publish(EntryEvents.requested(entry, residents));
    notifier.urgent(residents, NotificationRequested.GATE, "gate.entry.requested",
        params(entry), "entry:" + entry.getId() + ":requested");
    UUID society = TenantContext.activeSocietyId();
    afterCommit(() -> pending.put(society, entry.getId(), entry.getExpiresAt()));
    return new EntryView(entry, visitors.view(visitor));
  }

  @Transactional
  public EntryView decide(UUID id, boolean approve) {
    EntryLog entry = require(id);
    access.requireCanDecide(entry.getFlatId());
    entry.decide(approve, access.userId(), clock.instant());
    entries.save(entry);
    events.publish(EntryEvents.decided(entry));
    if (entry.getGuardId() != null) {
      notifier.info(List.of(entry.getGuardId()), NotificationRequested.GATE,
          approve ? "gate.entry.approved" : "gate.entry.denied", params(entry),
          "entry:" + entry.getId() + ":decided");
    }
    UUID society = TenantContext.activeSocietyId();
    afterCommit(() -> pending.remove(society, entry.getId()));
    return view(entry);
  }

  @Transactional
  public EntryView checkIn(UUID id) {
    EntryLog entry = require(id);
    entry.checkIn(clock.instant(), access.userId());
    entries.save(entry);
    events.publish(EntryEvents.checkedIn(entry));
    return view(entry);
  }

  @Transactional
  public EntryView checkOut(UUID id) {
    EntryLog entry = require(id);
    entry.checkOut(clock.instant());
    entries.save(entry);
    events.publish(EntryEvents.checkedOut(entry));
    return view(entry);
  }

  /** Pre-approved guest: the guard types the OTP or scans the QR; the pass admits them directly. */
  @Transactional
  public EntryView admitWithPass(PassEntry cmd) {
    Instant now = clock.instant();
    GatePass pass = passes.use(cmd.code(), cmd.qrToken()).pass();
    String name = firstNonBlank(cmd.visitorName(), pass.getGuestName(), "Guest");
    Visitor visitor = visitors.record(name, cmd.visitorPhone(), cmd.photoMediaId(), now);
    String label = directory.labels(List.of(pass.getFlatId())).get(pass.getFlatId());
    EntryLog entry = entries.save(EntryLog.admitted(
        new Arrival(pass.getFlatId(), label, cmd.gateId(), visitor.getId(), visitor.getName(), pass.getKind(), null,
            cmd.vehicleReg(), cmd.photoMediaId(), access.userId()),
        now, "PASS", pass.getId(), null));
    events.publish(EntryEvents.checkedIn(entry));
    notifier.info(directory.residentUserIds(pass.getFlatId()), NotificationRequested.GATE, "gate.pass.used",
        params(entry), "entry:" + entry.getId() + ":pass");
    return new EntryView(entry, visitors.view(visitor));
  }

  /** Domestic staff admitted at the gate (called by staff attendance, in its transaction). */
  @Transactional(propagation = Propagation.MANDATORY)
  public EntryLog admitStaff(UUID staffId, String staffName, List<UUID> flatIds, UUID gateId, Instant at) {
    UUID flatId = flatIds.getFirst();
    String label = directory.labels(List.of(flatId)).get(flatId);
    EntryLog entry = entries.save(EntryLog.admitted(
        new Arrival(flatId, label, gateId, null, staffName, "STAFF", null, null, null, access.userId()),
        at, "ONLINE", null, staffId));
    events.publish(EntryEvents.checkedIn(entry));
    notifier.info(directory.residentUserIds(flatIds), NotificationRequested.GATE, "gate.staff.arrived",
        Map.of("staffName", staffName, "flatLabel", label == null ? "" : label), "entry:" + entry.getId() + ":staff");
    return entry;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void closeStaffEntry(UUID entryId, Instant at) {
    entries.findById(entryId).filter(e -> e.status() == Status.IN).ifPresent(e -> {
      e.checkOut(at);
      entries.save(e);
      events.publish(EntryEvents.checkedOut(e));
    });
  }

  /** Edge agent pushes entries it admitted while offline; {@code clientEntryId} makes it idempotent. */
  @Transactional
  public List<EdgeResult> syncEdge(List<EdgeEntry> batch) {
    List<EdgeResult> results = new ArrayList<>();
    for (EdgeEntry item : batch) {
      if (item.clientEntryId() == null || item.clientEntryId().isBlank()) {
        results.add(new EdgeResult(item.clientEntryId(), null, "REJECTED", List.of("CLIENT_ENTRY_ID_REQUIRED")));
        continue;
      }
      Optional<EntryLog> existing = entries.findBySocietyIdAndClientEntryId(society(), item.clientEntryId());
      if (existing.isPresent()) {
        results.add(new EdgeResult(item.clientEntryId(), existing.get().getId(), "DUPLICATE", List.of()));
        continue;
      }
      List<String> warnings = new ArrayList<>();
      Instant at = item.inAt() == null ? clock.instant() : item.inAt();
      Optional<GatePass> pass = (item.passCode() != null || item.qrToken() != null)
          ? passes.useOffline(item.passCode(), item.qrToken(), at, warnings) : Optional.empty();
      UUID flatId = pass.map(GatePass::getFlatId).orElse(item.flatId());
      if (flatId == null) {
        results.add(new EdgeResult(item.clientEntryId(), null, "REJECTED", List.of("FLAT_REQUIRED")));
        continue;
      }
      String purpose = pass.map(GatePass::getKind).orElse(upper(firstNonBlank(item.purpose(), "GUEST", "GUEST")));
      String name = firstNonBlank(item.visitorName(), pass.map(GatePass::getGuestName).orElse(null), "Guest");
      String label = directory.labels(List.of(flatId)).get(flatId);
      EntryLog entry = EntryLog.admitted(new Arrival(flatId, label, item.gateId(), null, name, purpose, null,
          item.vehicleReg(), null, access.userId()), at, "EDGE", pass.map(GatePass::getId).orElse(null), null);
      entry.markEdge(item.clientEntryId());
      entries.save(entry);
      events.publish(EntryEvents.checkedIn(entry));
      if (item.outAt() != null && item.outAt().isAfter(at)) {
        entry.checkOut(item.outAt());
        entries.save(entry);
        events.publish(EntryEvents.checkedOut(entry));
      }
      results.add(new EdgeResult(item.clientEntryId(), entry.getId(), "CREATED", warnings));
    }
    return results;
  }

  /** Guard console: requests still waiting for a resident. */
  @Transactional(readOnly = true)
  public List<EntryView> pending() {
    return views(entries.findBySocietyIdAndStatusOrderByRequestedAtAsc(society(), Status.REQUESTED.name(),
        Limit.of(200)));
  }

  @Transactional(readOnly = true)
  public EntryView get(UUID id) {
    EntryLog entry = require(id);
    access.requireFlatView(entry.getFlatId());
    return view(entry);
  }

  /** The entry/exit log, newest first. Residents only see their own flats. */
  @Transactional(readOnly = true)
  public CursorPage<EntryView> list(Filter f) {
    int limit = CursorPage.clampLimit(f.limit());
    List<UUID> flats;
    if (f.flatId() != null) {
      access.requireFlatView(f.flatId());
      flats = List.of(f.flatId());
    } else if (access.canSeeSocietyLog()) {
      flats = null;
    } else {
      flats = access.myFlatIds();
      if (flats.isEmpty()) {
        return new CursorPage<>(List.of(), null);
      }
    }
    CursorPage.Cursor cursor = CursorPage.decode(f.cursor());
    UUID society = society();
    List<UUID> flatScope = flats;
    Specification<EntryLog> spec = (root, q, cb) -> {
      var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
      p.add(cb.equal(root.get("societyId"), society));
      if (flatScope != null) {
        p.add(root.get("flatId").in(flatScope));
      }
      if (f.status() != null && !f.status().isBlank()) {
        p.add(cb.equal(root.get("status"), upper(f.status())));
      }
      if (f.from() != null) {
        p.add(cb.greaterThanOrEqualTo(root.get("requestedAt"), f.from()));
      }
      if (f.to() != null) {
        p.add(cb.lessThan(root.get("requestedAt"), f.to()));
      }
      if (cursor != null) {
        p.add(cb.or(cb.lessThan(root.get("requestedAt"), cursor.createdAt()),
            cb.and(cb.equal(root.get("requestedAt"), cursor.createdAt()), cb.lessThan(root.get("id"), cursor.id()))));
      }
      return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
    List<EntryLog> rows = entries.findBy(spec, query -> query
        .sortBy(Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"))).limit(limit + 1).all());
    Map<UUID, VisitorView> byId = visitors.views(rows.stream().map(EntryLog::getVisitorId).toList());
    return CursorPage.of(rows, limit, e -> new EntryView(e, byId.get(e.getVisitorId())),
        e -> new CursorPage.Cursor(e.getRequestedAt(), e.getId()));
  }

  /** Expiry job, one society per transaction: requests past the society timeout become EXPIRED. */
  @Transactional
  public int expireDue(Instant now) {
    List<EntryLog> due = entries.findBySocietyIdAndStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
        society(), Status.REQUESTED.name(), now, Limit.of(500));
    int expired = 0;
    for (EntryLog e : due) {
      if (e.expireIfDue(now)) {
        entries.save(e);
        events.publish(EntryEvents.expired(e));
        if (e.getGuardId() != null) {
          notifier.info(List.of(e.getGuardId()), NotificationRequested.GATE, "gate.entry.expired", params(e),
              "entry:" + e.getId() + ":expired");
        }
        expired++;
      }
    }
    return expired;
  }

  @Transactional
  public int purgeBefore(Instant before) {
    return entries.purge(society(), before);
  }

  private List<EntryView> views(List<EntryLog> rows) {
    Map<UUID, VisitorView> byId = visitors.views(rows.stream().map(EntryLog::getVisitorId).toList());
    return rows.stream().map(e -> new EntryView(e, byId.get(e.getVisitorId()))).toList();
  }

  private EntryView view(EntryLog e) {
    return new EntryView(e, e.getVisitorId() == null ? null : visitors.views(List.of(e.getVisitorId()))
        .get(e.getVisitorId()));
  }

  private EntryLog require(UUID id) {
    return entries.findById(id).orElseThrow(() -> ProblemException.notFound("entry", id));
  }

  private static Map<String, String> params(EntryLog e) {
    Map<String, String> p = new HashMap<>();
    p.put("entryId", e.getId().toString());
    p.put("visitorName", e.getVisitorName() == null ? "" : e.getVisitorName());
    p.put("flatLabel", e.getFlatLabel() == null ? "" : e.getFlatLabel());
    p.put("purpose", e.getPurpose());
    if (e.getCompany() != null) {
      p.put("company", e.getCompany());
    }
    return p;
  }

  private static void afterCommit(Runnable r) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          r.run();
        }
      });
    } else {
      r.run();
    }
  }

  private static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase();
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v.trim();
      }
    }
    return null;
  }

  private static UUID society() {
    return TenantContext.activeSocietyId();
  }
}
