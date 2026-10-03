package in.societyos.billing.ledger.application;

import in.societyos.billing.common.Paise;
import in.societyos.billing.ledger.domain.Journal;
import in.societyos.billing.ledger.domain.LedgerAccount;
import in.societyos.billing.ledger.domain.LedgerEntry;
import in.societyos.billing.ledger.infrastructure.ChartOfAccounts;
import in.societyos.billing.ledger.infrastructure.LedgerAccountRepository;
import in.societyos.billing.ledger.infrastructure.LedgerEntryRepository;
import in.societyos.billing.platform.core.UuidV7;
import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.roster.application.BillingAccess;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The society's double-entry ledger. Every money movement (bill, late fee, adjustment, payment,
 * expense) is posted here as one balanced transaction; a flat's outstanding is the balance of
 * its MEMBER_RECEIVABLE sub-ledger (positive = owes, negative = advance).
 */
@Service
public class LedgerService {

  public record StatementLine(Instant at, LocalDate date, String refType, UUID refId, String narration,
      long debitPaise, long creditPaise, long balancePaise) {}

  public record AccountBalance(String code, String name, String kind, long debitPaise, long creditPaise,
      long balancePaise) {}

  private final LedgerEntryRepository entries;
  private final LedgerAccountRepository accounts;
  private final ChartOfAccounts chart;
  private final BillingAccess access;

  public LedgerService(LedgerEntryRepository entries, LedgerAccountRepository accounts, ChartOfAccounts chart,
      BillingAccess access) {
    this.entries = entries;
    this.accounts = accounts;
    this.chart = chart;
    this.access = access;
  }

  /** Posts a balanced journal in the caller's transaction; returns the transaction id. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID post(Journal journal) {
    List<Journal.Line> lines = journal.lines();
    if (lines.isEmpty()) {
      return null;
    }
    Map<String, LedgerAccount> byCode = accountsByCode();
    if (byCode.size() < Journal.Account.values().length) {
      chart.ensure();
      byCode = accountsByCode();
    }
    UUID txnId = UuidV7.next();
    Instant now = Instant.now();
    List<LedgerEntry> rows = new ArrayList<>(lines.size());
    for (Journal.Line l : lines) {
      rows.add(new LedgerEntry(txnId, byCode.get(l.account().name()), journal, l, now));
    }
    entries.saveAll(rows);
    return txnId;
  }

  @Transactional(readOnly = true)
  public long outstandingOf(UUID flatId) {
    return entries.receivableOf(flatId);
  }

  /** flat id → outstanding (only flats with ledger activity). */
  @Transactional(readOnly = true)
  public Map<UUID, Long> outstandingByFlat() {
    Map<UUID, Long> result = new HashMap<>();
    entries.receivables().forEach(b -> result.put(b.getFlatId(), b.getBalance()));
    return result;
  }

  /** A flat's statement for the caller: all flats with bill:view, own flats for residents. */
  @Transactional(readOnly = true)
  public List<StatementLine> statementFor(UUID flatId) {
    access.requireFlatRead(flatId);
    return statement(flatId);
  }

  @Transactional(readOnly = true)
  public List<StatementLine> statement(UUID flatId) {
    long running = 0;
    List<StatementLine> out = new ArrayList<>();
    for (LedgerEntry e : entries.statementOf(flatId)) {
      running = Math.addExact(running, e.getDebitPaise() - e.getCreditPaise());
      out.add(new StatementLine(e.getAt(), e.getEntryDate(), e.getRefType(), e.getRefId(), e.getNarration(),
          e.getDebitPaise(), e.getCreditPaise(), running));
    }
    return out;
  }

  @Transactional(readOnly = true)
  public List<LedgerEntry> entries(LocalDate from, LocalDate to) {
    checkRange(from, to);
    return entries.between(from, to);
  }

  /** Trial balance up to a date: per account debit, credit and balance (debit - credit). */
  @Transactional(readOnly = true)
  public List<AccountBalance> trialBalance(LocalDate to) {
    Map<String, LedgerEntryRepository.Balance> totals = entries.accountTotals(to).stream()
        .collect(Collectors.toMap(LedgerEntryRepository.Balance::getAccount, Function.identity()));
    List<AccountBalance> out = new ArrayList<>();
    for (LedgerAccount a : accounts.findAllByOrderByCodeAsc()) {
      var t = totals.get(a.getCode());
      long d = t == null ? 0 : t.getDebit();
      long c = t == null ? 0 : t.getCredit();
      out.add(new AccountBalance(a.getCode(), a.getName(), a.getKind(), d, c, d - c));
    }
    return out;
  }

  /** Journal export (one row per entry) in a Tally-friendly CSV; amounts in rupees with 2 decimals. */
  @Transactional(readOnly = true)
  public String exportCsv(LocalDate from, LocalDate to) {
    StringBuilder csv = new StringBuilder("date,txn_id,account,flat_id,debit,credit,ref_type,ref_id,narration\n");
    for (LedgerEntry e : entries(from, to)) {
      csv.append(e.getEntryDate()).append(',')
          .append(e.getTxnId()).append(',')
          .append(e.getAccountCode()).append(',')
          .append(e.getFlatId() == null ? "" : e.getFlatId()).append(',')
          .append(Paise.rupees(e.getDebitPaise())).append(',')
          .append(Paise.rupees(e.getCreditPaise())).append(',')
          .append(e.getRefType()).append(',')
          .append(e.getRefId()).append(',')
          .append(quote(e.getNarration())).append('\n');
    }
    return csv.toString();
  }

  private Map<String, LedgerAccount> accountsByCode() {
    return accounts.findAllByOrderByCodeAsc().stream()
        .collect(Collectors.toMap(LedgerAccount::getCode, Function.identity()));
  }

  private static void checkRange(LocalDate from, LocalDate to) {
    if (from.isAfter(to) || from.plusYears(5).isBefore(to)) {
      throw ProblemException.badRequest("INVALID_RANGE", "from must be before to, at most 5 years apart");
    }
  }

  /** CSV-quotes a value and defuses spreadsheet formulas (=, +, -, @). */
  static String quote(String value) {
    String v = value == null ? "" : value;
    if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
      v = "'" + v;
    }
    return "\"" + v.replace("\"", "\"\"") + "\"";
  }
}
