package in.societyos.billing.bill.application;

import in.societyos.billing.bill.domain.Bill;
import in.societyos.billing.bill.domain.BillAdjustment;
import in.societyos.billing.bill.domain.BillLine;
import in.societyos.billing.bill.infrastructure.BillAdjustmentRepository;
import in.societyos.billing.bill.infrastructure.BillLineRepository;
import in.societyos.billing.bill.infrastructure.BillRepository;
import in.societyos.billing.common.BillingClock;
import in.societyos.billing.ledger.application.LedgerService;
import in.societyos.billing.ledger.domain.Journal;
import in.societyos.billing.ledger.domain.Journal.Account;
import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.platform.jpa.DocumentNumberService;
import in.societyos.billing.roster.application.BillingAccess;
import in.societyos.billing.roster.application.RosterService;
import in.societyos.billing.roster.domain.FlatRef;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bill queries (scoped to the caller's flats for residents), adjustments and dues. */
@Service
public class BillService {

  public record BillDetail(Bill bill, List<BillLine> lines, List<BillAdjustment> adjustments) {}

  /** A flat's position: ledger outstanding (negative = advance) and what is past due. */
  public record FlatDues(UUID flatId, String flatLabel, long outstandingPaise, long overduePaise, int openBills,
      LocalDate oldestDueDate) {}

  private final BillRepository bills;
  private final BillLineRepository lines;
  private final BillAdjustmentRepository adjustments;
  private final LedgerService ledger;
  private final DocumentNumberService numbers;
  private final BillingAccess access;
  private final RosterService roster;
  private final BillingClock clock;

  public BillService(BillRepository bills, BillLineRepository lines, BillAdjustmentRepository adjustments,
      LedgerService ledger, DocumentNumberService numbers, BillingAccess access, RosterService roster,
      BillingClock clock) {
    this.bills = bills;
    this.lines = lines;
    this.adjustments = adjustments;
    this.ledger = ledger;
    this.numbers = numbers;
    this.access = access;
    this.roster = roster;
    this.clock = clock;
  }

  /** Published bills; a resident sees only the flats they belong to. */
  @Transactional(readOnly = true)
  public List<Bill> list(UUID flatId, String period, String status, int limit) {
    Collection<UUID> flats;
    if (flatId != null) {
      access.requireFlatRead(flatId);
      flats = List.of(flatId);
    } else if (access.canViewAllBills()) {
      flats = null;
    } else {
      flats = access.ownFlats();
      if (flats.isEmpty()) {
        return List.of();
      }
    }
    Specification<Bill> spec = (root, q, cb) -> {
      List<Predicate> p = new ArrayList<>();
      p.add(cb.notEqual(root.get("status"), "DRAFT"));
      if (flats != null) {
        p.add(root.get("flatId").in(flats));
      }
      if (period != null && !period.isBlank()) {
        p.add(cb.equal(root.get("period"), period.replace("-", "")));
      }
      if (status != null && !status.isBlank()) {
        p.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
      }
      return cb.and(p.toArray(Predicate[]::new));
    };
    return bills.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Order.desc("dueDate"), Sort.Order.asc("flatLabel"))))
        .getContent();
  }

  @Transactional(readOnly = true)
  public BillDetail detail(UUID id) {
    Bill bill = require(id);
    if (!bill.isPublished()) {
      if (!access.canViewAllBills()) {
        throw ProblemException.notFound("bill", id);
      }
    } else {
      access.requireFlatRead(bill.getFlatId());
    }
    return new BillDetail(bill, lines.findByBillIdOrderBySortOrderAsc(id), adjustments.findByBillIdOrderByCreatedAtAsc(id));
  }

  @Transactional(readOnly = true)
  public Bill require(UUID id) {
    return bills.findById(id).orElseThrow(() -> ProblemException.notFound("bill", id));
  }

  /** Credit note (reduces the balance, at most to zero) or debit note (adds to it). */
  @Transactional
  public BillAdjustment adjust(UUID billId, String kind, long amountPaise, String reason) {
    Bill bill = require(billId);
    String k = kind == null ? "" : kind.trim().toUpperCase();
    if (!Set.of("CREDIT", "DEBIT").contains(k)) {
      throw ProblemException.badRequest("INVALID_ADJUSTMENT", "kind must be CREDIT or DEBIT");
    }
    if (amountPaise <= 0) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "amountPaise must be positive");
    }
    if (!bill.isPublished() || "CANCELLED".equals(bill.getStatus())) {
      throw ProblemException.unprocessable("BILL_NOT_PUBLISHED", "Only a published bill can be adjusted");
    }
    boolean credit = "CREDIT".equals(k);
    if (credit && amountPaise > bill.getBalancePaise()) {
      throw ProblemException.unprocessable("CREDIT_EXCEEDS_BALANCE",
          "A credit note can reduce the balance to zero, not below");
    }
    bill.adjust(credit ? -amountPaise : amountPaise);
    bills.save(bill);
    BillAdjustment adj = adjustments.save(new BillAdjustment(bill.getId(), bill.getFlatId(),
        numbers.next(credit ? "CN" : "DN"), k, amountPaise, reason.trim(), clock.now()));
    Journal j = new Journal(credit ? "CREDIT_NOTE" : "DEBIT_NOTE", adj.getId(),
        adj.getNumber() + " on " + bill.getNumber() + ": " + adj.getReason(), clock.today());
    if (credit) {
      j.debit(Account.MAINTENANCE_INCOME, amountPaise).credit(Account.MEMBER_RECEIVABLE, bill.getFlatId(), amountPaise);
    } else {
      j.debit(Account.MEMBER_RECEIVABLE, bill.getFlatId(), amountPaise).credit(Account.MAINTENANCE_INCOME, amountPaise);
    }
    ledger.post(j);
    return adj;
  }

  /** Open bills of a flat, oldest due first, locked for the caller's transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Bill> openForUpdate(UUID flatId) {
    return bills.openForUpdate(flatId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Bill save(Bill bill) {
    return bills.save(bill);
  }

  /** Dues of every flat of the roster (bill:view). */
  @Transactional(readOnly = true)
  public List<FlatDues> dues() {
    return dues(roster.flats());
  }

  /** Dues of the caller's own flats. */
  @Transactional(readOnly = true)
  public List<FlatDues> myDues() {
    List<UUID> own = access.ownFlats();
    return dues(new ArrayList<>(roster.flatsById(own).values()));
  }

  @Transactional(readOnly = true)
  public FlatDues duesOf(UUID flatId) {
    access.requireFlatRead(flatId);
    return dues(List.of(roster.requireFlat(flatId))).getFirst();
  }

  private List<FlatDues> dues(List<FlatRef> flats) {
    Map<UUID, Long> outstanding = ledger.outstandingByFlat();
    Map<UUID, List<Bill>> open = new HashMap<>();
    Set<UUID> ids = new LinkedHashSet<>();
    flats.forEach(f -> ids.add(f.getId()));
    for (Bill b : bills.allOpen()) {
      if (ids.contains(b.getFlatId())) {
        open.computeIfAbsent(b.getFlatId(), k -> new ArrayList<>()).add(b);
      }
    }
    LocalDate today = clock.today();
    List<FlatDues> out = new ArrayList<>();
    for (FlatRef f : flats.stream().sorted(java.util.Comparator.comparing(FlatRef::getLabel)).toList()) {
      List<Bill> o = open.getOrDefault(f.getId(), List.of());
      long overdue = o.stream().filter(b -> b.getDueDate().isBefore(today)).mapToLong(Bill::getBalancePaise)
          .reduce(0, Math::addExact);
      LocalDate oldest = o.stream().map(Bill::getDueDate).min(LocalDate::compareTo).orElse(null);
      out.add(new FlatDues(f.getId(), f.getLabel(), outstanding.getOrDefault(f.getId(), 0L), overdue, o.size(), oldest));
    }
    return out;
  }
}
