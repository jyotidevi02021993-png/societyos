package in.societyos.billing.expense.api;

import in.societyos.billing.expense.application.ExpenseService;
import in.societyos.billing.expense.application.ExpenseService.ExpenseInput;
import in.societyos.billing.expense.domain.Budget;
import in.societyos.billing.expense.domain.Expense;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExpenseController {

  private final ExpenseService expenses;

  ExpenseController(ExpenseService expenses) {
    this.expenses = expenses;
  }

  record ExpenseRequest(@NotBlank @Size(max = 60) String category, @Size(max = 300) String description,
      @Positive long amountPaise, String paidFrom, LocalDate spentOn, UUID assetId, UUID vendorId, UUID towerId,
      @Size(max = 60) String department, @Size(max = 100) String reference) {
    ExpenseInput input() {
      return new ExpenseInput(category, description, amountPaise, paidFrom, spentOn, assetId, vendorId, towerId,
          department, reference);
    }
  }

  record ExpenseResponse(UUID id, String kind, String category, String description, long amountPaise,
      String paidFrom, LocalDate spentOn, String financialYear, UUID assetId, UUID vendorId, UUID towerId,
      String department, String reference) {
    static ExpenseResponse from(Expense e) {
      return new ExpenseResponse(e.getId(), e.getKind(), e.getCategory(), e.getDescription(), e.getAmountPaise(),
          e.getPaidFrom(), e.getSpentOn(), e.getFinancialYear(), e.getAssetId(), e.getVendorId(), e.getTowerId(),
          e.getDepartment(), e.getReference());
    }
  }

  record BudgetRequest(@NotBlank String financialYear, @NotBlank @Size(max = 60) String category,
      @Positive long amountPaise, @Size(max = 300) String notes) {}

  record BudgetUpdate(@Positive long amountPaise, @Size(max = 300) String notes) {}

  record Decision(@Size(max = 300) String note) {}

  record BudgetResponse(UUID id, String financialYear, String category, long amountPaise, String notes, String status,
      UUID createdBy, UUID decidedBy, Instant decidedAt, String decisionNote, Long actualPaise, Long remainingPaise) {
    static BudgetResponse from(Budget b, Long actual, Long remaining) {
      return new BudgetResponse(b.getId(), b.getFinancialYear(), b.getCategory(), b.getAmountPaise(), b.getNotes(),
          b.getStatus(), b.getCreatedBy(), b.getDecidedBy(), b.getDecidedAt(), b.getDecisionNote(), actual, remaining);
    }
  }

  @PostMapping("/v1/expenses")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@perm.has('expense:record')")
  ExpenseResponse record(@Valid @RequestBody ExpenseRequest r) {
    return ExpenseResponse.from(expenses.record(r.input()));
  }

  @PostMapping("/v1/vendor-payments")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@perm.has('vendorpayment:record')")
  ExpenseResponse vendorPayment(@Valid @RequestBody ExpenseRequest r) {
    return ExpenseResponse.from(expenses.recordVendorPayment(r.input()));
  }

  @GetMapping("/v1/expenses")
  @PreAuthorize("@perm.hasAny('expense:view', 'expense:record')")
  List<ExpenseResponse> list(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String category) {
    return expenses.list(from, to, category).stream().map(ExpenseResponse::from).toList();
  }

  @GetMapping("/v1/expenses/{id}")
  @PreAuthorize("@perm.hasAny('expense:view', 'expense:record')")
  ExpenseResponse get(@PathVariable UUID id) {
    return ExpenseResponse.from(expenses.require(id));
  }

  @GetMapping("/v1/budgets")
  @PreAuthorize("@perm.hasAny('expense:view', 'expense:record', 'budget:approve')")
  List<BudgetResponse> budgets(@RequestParam(required = false) String financialYear) {
    return expenses.budgets(financialYear).stream()
        .map(v -> BudgetResponse.from(v.budget(), v.actualPaise(), v.remainingPaise())).toList();
  }

  @PostMapping("/v1/budgets")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@perm.has('expense:record')")
  BudgetResponse createBudget(@Valid @RequestBody BudgetRequest r) {
    return BudgetResponse.from(expenses.createBudget(r.financialYear(), r.category(), r.amountPaise(), r.notes()),
        null, null);
  }

  @PutMapping("/v1/budgets/{id}")
  @PreAuthorize("@perm.has('expense:record')")
  BudgetResponse updateBudget(@PathVariable UUID id, @Valid @RequestBody BudgetUpdate r) {
    return BudgetResponse.from(expenses.updateBudget(id, r.amountPaise(), r.notes()), null, null);
  }

  @PostMapping("/v1/budgets/{id}/approve")
  @PreAuthorize("@perm.has('budget:approve')")
  BudgetResponse approve(@PathVariable UUID id, @RequestBody(required = false) Decision d) {
    return BudgetResponse.from(expenses.decide(id, true, d == null ? null : d.note()), null, null);
  }

  @PostMapping("/v1/budgets/{id}/reject")
  @PreAuthorize("@perm.has('budget:approve')")
  BudgetResponse reject(@PathVariable UUID id, @RequestBody(required = false) Decision d) {
    return BudgetResponse.from(expenses.decide(id, false, d == null ? null : d.note()), null, null);
  }
}
