package in.societyos.billing.expense.domain;

import in.societyos.billing.common.BillingEvent;
import java.time.LocalDate;
import java.util.UUID;

/** {@code billing.expense.recorded}, as in the catalogue. {@code at} is the date spent. */
public record ExpenseRecorded(UUID expenseId, String category, long amountPaise, UUID assetId, UUID vendorId,
    LocalDate at) implements BillingEvent {

  @Override
  public String type() {
    return "billing.expense.recorded";
  }

  @Override
  public UUID aggregateId() {
    return expenseId;
  }
}
