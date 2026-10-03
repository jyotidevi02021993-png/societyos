package in.societyos.billing.expense.application;

import in.societyos.billing.common.BillingClock;
import in.societyos.billing.expense.domain.Budget;
import in.societyos.billing.expense.domain.Expense;
import in.societyos.billing.expense.domain.ExpenseRecorded;
import in.societyos.billing.expense.domain.FinancialYear;
import in.societyos.billing.expense.infrastructure.BudgetRepository;
import in.societyos.billing.expense.infrastructure.ExpenseRepository;
import in.societyos.billing.ledger.application.LedgerService;
import in.societyos.billing.ledger.domain.Journal;
import in.societyos.billing.ledger.domain.Journal.Account;
import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.platform.core.tenant.TenantContext;
import in.societyos.billing.platform.events.DomainEvents;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expenses and vendor payments (Dr Expenses · Cr Bank/Cash, {@code billing.expense.recorded}) and
 * yearly budgets per category with maker-checker approval and actual spend against them.
 */
@Service
public class ExpenseService {

  public record ExpenseInput(String category, String description, long amountPaise, String paidFrom,
      LocalDate spentOn, UUID assetId, UUID vendorId, UUID towerId, String department, String reference) {}

  public record BudgetView(Budget budget, long actualPaise, long remainingPaise) {}

  private final ExpenseRepository expenses;
  private final BudgetRepository budgets;
  private final LedgerService ledger;
  private final DomainEvents events;
  private final BillingClock clock;

  public ExpenseService(ExpenseRepository expenses, BudgetRepository budgets, LedgerService ledger,
      DomainEvents events, BillingClock clock) {
    this.expenses = expenses;
    this.budgets = budgets;
    this.ledger = ledger;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public Expense record(ExpenseInput in) {
    return save("EXPENSE", in);
  }

  /** A payment to a vendor (e.g. against an approved invoice): an expense that names the vendor. */
  @Transactional
  public Expense recordVendorPayment(ExpenseInput in) {
    if (in.vendorId() == null) {
      throw ProblemException.badRequest("VENDOR_REQUIRED", "A vendor payment needs a vendorId");
    }
    if (in.reference() == null || in.reference().isBlank()) {
      throw ProblemException.badRequest("REFERENCE_REQUIRED", "A vendor payment needs an invoice or UTR reference");
    }
    return save("VENDOR_PAYMENT", in);
  }

  @Transactional(readOnly = true)
  public List<Expense> list(LocalDate from, LocalDate to, String category) {
    LocalDate t = to == null ? clock.today() : to;
    LocalDate f = from == null ? t.minusMonths(3) : from;
    if (f.isAfter(t)) {
      throw ProblemException.badRequest("INVALID_RANGE", "from must not be after to");
    }
    return expenses.between(f, t, category == null ? "" : category.trim());
  }

  @Transactional(readOnly = true)
  public Expense require(UUID id) {
    return expenses.findById(id).orElseThrow(() -> ProblemException.notFound("expense", id));
  }

  @Transactional
  public Budget createBudget(String financialYear, String category, long amountPaise, String notes) {
    String fy = financialYear == null ? "" : financialYear.trim();
    if (!FinancialYear.isValid(fy)) {
      throw ProblemException.badRequest("INVALID_FINANCIAL_YEAR", "financialYear is like 2026-27");
    }
    String cat = category.trim();
    if (budgets.liveExists(fy, cat)) {
      throw ProblemException.conflict("BUDGET_EXISTS", "A budget for this category and year exists");
    }
    if (amountPaise <= 0) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "amountPaise must be positive");
    }
    return budgets.save(new Budget(fy, cat, amountPaise, notes));
  }

  @Transactional
  public Budget updateBudget(UUID id, long amountPaise, String notes) {
    Budget b = requireBudget(id);
    if (!b.isDraft()) {
      throw ProblemException.unprocessable("BUDGET_DECIDED", "Only a draft budget can be changed");
    }
    if (amountPaise <= 0) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "amountPaise must be positive");
    }
    b.update(amountPaise, notes);
    return budgets.save(b);
  }

  /** Maker-checker: the author of a budget cannot decide it. */
  @Transactional
  public Budget decide(UUID id, boolean approve, String note) {
    Budget b = requireBudget(id);
    if (!b.isDraft()) {
      throw ProblemException.unprocessable("BUDGET_DECIDED", "This budget is already " + b.getStatus());
    }
    UUID me = TenantContext.userId().orElse(null);
    if (me == null || Objects.equals(me, b.getCreatedBy())) {
      throw ProblemException.forbidden("SELF_APPROVAL", "A budget is approved by someone other than its author");
    }
    if (!approve && (note == null || note.isBlank())) {
      throw ProblemException.badRequest("REASON_REQUIRED", "Give a reason to reject");
    }
    b.decide(approve, me, note, clock.now());
    return budgets.save(b);
  }

  /** Budgets of a year with the actual spend recorded against each category. */
  @Transactional(readOnly = true)
  public List<BudgetView> budgets(String financialYear) {
    String fy = financialYear == null || financialYear.isBlank() ? FinancialYear.of(clock.today()) : financialYear.trim();
    Map<String, Long> actual = expenses.totalsFor(fy).stream()
        .collect(Collectors.toMap(ExpenseRepository.CategoryTotal::getCategory, ExpenseRepository.CategoryTotal::getTotal));
    return budgets.findByFinancialYearOrderByCategoryAsc(fy).stream().map(b -> {
      long spent = actual.getOrDefault(b.getCategory().toUpperCase(), 0L);
      return new BudgetView(b, spent, b.getAmountPaise() - spent);
    }).toList();
  }

  private Budget requireBudget(UUID id) {
    return budgets.findById(id).orElseThrow(() -> ProblemException.notFound("budget", id));
  }

  private Expense save(String kind, ExpenseInput in) {
    String paidFrom = in.paidFrom() == null ? "BANK" : in.paidFrom().trim().toUpperCase();
    if (!paidFrom.equals("BANK") && !paidFrom.equals("CASH")) {
      throw ProblemException.badRequest("INVALID_PAID_FROM", "paidFrom must be BANK or CASH");
    }
    if (in.amountPaise() <= 0) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "amountPaise must be positive");
    }
    LocalDate spentOn = in.spentOn() == null ? clock.today() : in.spentOn();
    if (spentOn.isAfter(clock.today())) {
      throw ProblemException.badRequest("INVALID_DATE", "spentOn cannot be in the future");
    }
    Expense e = expenses.save(new Expense(kind, in.category().trim(), in.description(), in.amountPaise(), paidFrom,
        spentOn, in.assetId(), in.vendorId(), in.towerId(), in.department(), in.reference()));
    ledger.post(new Journal(kind, e.getId(), e.getCategory() + (e.getDescription() == null ? "" : ": "
        + e.getDescription()), spentOn)
        .debit(Account.EXPENSES, e.getAmountPaise())
        .credit("CASH".equals(paidFrom) ? Account.CASH : Account.BANK, e.getAmountPaise()));
    events.publish(new ExpenseRecorded(e.getId(), e.getCategory(), e.getAmountPaise(), e.getAssetId(),
        e.getVendorId(), spentOn));
    return e;
  }
}
