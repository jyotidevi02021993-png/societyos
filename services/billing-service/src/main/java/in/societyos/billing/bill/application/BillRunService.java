package in.societyos.billing.bill.application;

import in.societyos.billing.bill.domain.Bill;
import in.societyos.billing.bill.domain.BillEvents;
import in.societyos.billing.bill.domain.BillLine;
import in.societyos.billing.bill.domain.BillRun;
import in.societyos.billing.bill.domain.DuesRules;
import in.societyos.billing.bill.infrastructure.BillLineRepository;
import in.societyos.billing.bill.infrastructure.BillRepository;
import in.societyos.billing.bill.infrastructure.BillRunLock;
import in.societyos.billing.bill.infrastructure.BillRunRepository;
import in.societyos.billing.charge.application.PendingChargeService;
import in.societyos.billing.charge.domain.PendingCharge;
import in.societyos.billing.common.BillingClock;
import in.societyos.billing.common.Paise;
import in.societyos.billing.ledger.application.LedgerService;
import in.societyos.billing.ledger.domain.Journal;
import in.societyos.billing.ledger.domain.Journal.Account;
import in.societyos.billing.notification.application.BillingNotifier;
import in.societyos.billing.payment.application.AllocationService;
import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.platform.core.tenant.TenantContext;
import in.societyos.billing.platform.events.DomainEvents;
import in.societyos.billing.platform.jpa.DocumentNumberService;
import in.societyos.billing.roster.application.RosterService;
import in.societyos.billing.roster.domain.BillingSettings;
import in.societyos.billing.roster.domain.FlatRef;
import in.societyos.billing.tariff.application.ChargeHeadService;
import in.societyos.billing.tariff.domain.TariffCalculator;
import in.societyos.billing.tariff.domain.TariffCalculator.FlatProfile;
import in.societyos.billing.tariff.domain.TariffCalculator.GstPolicy;
import in.societyos.billing.tariff.domain.TariffCalculator.OneOffCharge;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monthly bill runs: {@link #preview} computes one draft bill per flat (charge heads + pending
 * one-off charges + GST) so accounts can check the totals; {@link #publish} numbers the bills
 * (BILL-…), posts them to the ledger (Dr Member receivable · Cr income · Cr GST payable), applies
 * any advance the flat holds, and announces them. Runs hold {@code bill-run:<society>}.
 */
@Service
public class BillRunService {

  public record RunView(BillRun run, List<Bill> bills) {}

  private final BillRunRepository runs;
  private final BillRepository bills;
  private final BillLineRepository lines;
  private final BillRunLock lock;
  private final RosterService roster;
  private final ChargeHeadService heads;
  private final PendingChargeService charges;
  private final LedgerService ledger;
  private final AllocationService allocator;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final BillingNotifier notifier;
  private final BillingClock clock;
  private final JsonMapper json;

  public BillRunService(BillRunRepository runs, BillRepository bills, BillLineRepository lines, BillRunLock lock,
      RosterService roster, ChargeHeadService heads, PendingChargeService charges, LedgerService ledger,
      AllocationService allocator, DocumentNumberService numbers, DomainEvents events, BillingNotifier notifier,
      BillingClock clock, JsonMapper json) {
    this.runs = runs;
    this.bills = bills;
    this.lines = lines;
    this.lock = lock;
    this.roster = roster;
    this.heads = heads;
    this.charges = charges;
    this.ledger = ledger;
    this.allocator = allocator;
    this.numbers = numbers;
    this.events = events;
    this.notifier = notifier;
    this.clock = clock;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public List<BillRun> list() {
    return runs.findTop36ByOrderByPeriodDescCreatedAtDesc();
  }

  @Transactional(readOnly = true)
  public RunView get(UUID id) {
    BillRun run = require(id);
    return new RunView(run, bills.findByBillRunIdOrderByFlatLabelAsc(id));
  }

  /** Computes (or recomputes) the draft bills of a month. A published month cannot be re-run. */
  @Transactional
  public RunView preview(YearMonth month, LocalDate dueDateOverride) {
    lock.acquire();
    String period = DuesRules.period(month);
    runs.liveFor(period).ifPresent(existing -> {
      if (!existing.isPreview()) {
        throw ProblemException.conflict("BILL_RUN_PUBLISHED", "Bills for " + month + " are already published");
      }
      discardDrafts(existing);
      runs.saveAndFlush(existing);
    });

    BillingSettings settings = roster.settings();
    LocalDate billDate = clock.today();
    LocalDate dueDate = dueDateOverride != null ? dueDateOverride
        : DuesRules.dueDate(month, settings.getBillingDueDay(), billDate);
    if (dueDate.isBefore(billDate)) {
      throw ProblemException.badRequest("INVALID_DUE_DATE", "The due date cannot be before today");
    }
    BillRun run = runs.save(new BillRun(period, billDate, dueDate));

    List<TariffCalculator.Tariff> tariffs = heads.activeTariffs();
    Map<UUID, List<PendingCharge>> pending = charges.pendingByFlat();
    GstPolicy gst = new GstPolicy(settings.isGstRegistered(), settings.getGstRateBps(),
        settings.getGstExemptionThresholdPaise());
    List<String> warnings = new ArrayList<>();
    List<Bill> drafts = new ArrayList<>();
    List<BillLine> draftLines = new ArrayList<>();
    long amount = 0;
    long tax = 0;
    for (FlatRef flat : roster.flats()) {
      List<OneOffCharge> oneOffs = pending.getOrDefault(flat.getId(), List.of()).stream()
          .map(c -> new OneOffCharge(c.getId(), c.getDescription(), c.getAmountPaise(), c.isGstApplicable()))
          .toList();
      TariffCalculator.Result r = TariffCalculator.calculate(
          new FlatProfile(flat.getLabel(), flat.getAreaSqft(), flat.getFlatType(), flat.getStatus()),
          tariffs, oneOffs, gst);
      warnings.addAll(r.warnings());
      if (r.isEmpty()) {
        continue;
      }
      Bill bill = new Bill(run.getId(), flat.getId(), flat.getLabel(), period, billDate, dueDate, r.amountPaise(),
          r.gstPaise());
      drafts.add(bill);
      int order = 0;
      for (TariffCalculator.Line l : r.lines()) {
        draftLines.add(new BillLine(bill.getId(), l.kind(), l.code(), l.description(), l.amountPaise(), l.gstPaise(),
            l.sourceRef(), order++));
      }
      amount = Paise.plus(amount, r.amountPaise());
      tax = Paise.plus(tax, r.gstPaise());
    }
    if (tariffs.isEmpty()) {
      warnings.add("No active charge heads: only one-off charges were billed");
    }
    bills.saveAll(drafts);
    lines.saveAll(draftLines);
    run.totals(drafts.size(), amount, tax, json.writeValueAsString(warnings));
    runs.save(run);
    return new RunView(run, drafts);
  }

  @Transactional
  public void discard(UUID id) {
    lock.acquire();
    BillRun run = require(id);
    if (!run.isPreview()) {
      throw ProblemException.unprocessable("BILL_RUN_PUBLISHED", "A published bill run cannot be discarded");
    }
    discardDrafts(run);
    runs.save(run);
  }

  @Transactional
  public RunView publish(UUID id) {
    lock.acquire();
    BillRun run = require(id);
    if (!run.isPreview()) {
      throw ProblemException.unprocessable("BILL_RUN_PUBLISHED", "This bill run is already published");
    }
    Instant now = clock.now();
    List<Bill> runBills = bills.findByBillRunIdOrderByFlatLabelAsc(id);
    Map<UUID, List<BillLine>> linesByBill = lines.findByBillIdIn(runBills.stream().map(Bill::getId).toList())
        .stream().collect(Collectors.groupingBy(BillLine::getBillId));
    for (Bill bill : runBills) {
      long arrears = ledger.outstandingOf(bill.getFlatId());
      bill.publish(numbers.next("BILL"), arrears, now);
      bills.save(bill);
      List<BillLine> bl = linesByBill.getOrDefault(bill.getId(), List.of());
      long charges = bl.stream().filter(l -> "CHARGE".equals(l.getKind())).mapToLong(BillLine::getAmountPaise)
          .reduce(0, Math::addExact);
      long oneOff = bl.stream().filter(l -> "ONE_OFF".equals(l.getKind())).mapToLong(BillLine::getAmountPaise)
          .reduce(0, Math::addExact);
      ledger.post(new Journal("BILL", bill.getId(), bill.getNumber() + " " + bill.getFlatLabel() + " "
          + bill.getPeriod(), run.getBillDate())
          .debit(Account.MEMBER_RECEIVABLE, bill.getFlatId(), bill.getTotalPaise())
          .credit(Account.MAINTENANCE_INCOME, charges)
          .credit(Account.OTHER_INCOME, oneOff)
          .credit(Account.GST_PAYABLE, bill.getGstPaise()));
      this.charges.markBilled(bl.stream().map(BillLine::getSourceRef).filter(Objects::nonNull).toList(), bill.getId());
      events.publish(BillEvents.BillGenerated.of(bill));
      allocator.applyAdvances(bill.getFlatId());
      notifier.toFlat(bill.getFlatId(), BillingNotifier.BILL_PUBLISHED, Map.of(
          "flatLabel", bill.getFlatLabel(),
          "billNumber", bill.getNumber(),
          "period", YearMonth.parse(bill.getPeriod(), java.time.format.DateTimeFormatter.ofPattern("yyyyMM")).toString(),
          "amount", Paise.rupees(bill.getTotalPaise()),
          "dueDate", bill.getDueDate().toString()), "bill:" + bill.getId(), false);
    }
    if (!runBills.isEmpty()) {
      lines.lock(runBills.stream().map(Bill::getId).toList(), now);
    }
    run.publish(now, TenantContext.userId().orElse(null));
    runs.save(run);
    events.publish(new BillEvents.BillRunCompleted(run.getId(), run.getPeriod(), run.getBillCount(),
        run.getTotalPaise()));
    return new RunView(run, bills.findByBillRunIdOrderByFlatLabelAsc(id));
  }

  private BillRun require(UUID id) {
    return runs.findById(id).orElseThrow(() -> ProblemException.notFound("bill_run", id));
  }

  private void discardDrafts(BillRun run) {
    List<UUID> ids = bills.findByBillRunIdOrderByFlatLabelAsc(run.getId()).stream().map(Bill::getId).toList();
    if (!ids.isEmpty()) {
      lines.deleteDrafts(ids);
    }
    bills.deleteDrafts(run.getId());
    run.discard();
  }
}
