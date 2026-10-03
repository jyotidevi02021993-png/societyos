package in.societyos.billing.bill.domain;

import in.societyos.billing.common.BillingEvent;
import java.time.LocalDate;
import java.util.UUID;

/** Bill events, exactly as in contracts/events/CATALOGUE.md (billing section). */
public final class BillEvents {

  private BillEvents() {}

  public record BillRunCompleted(UUID billRunId, String period, int billCount, long totalPaise)
      implements BillingEvent {
    @Override public String type() { return "billing.billrun.completed"; }
    @Override public UUID aggregateId() { return billRunId; }
  }

  public record BillGenerated(UUID billId, UUID flatId, String number, String period, LocalDate dueDate,
      long amountPaise, long gstPaise, long totalPaise) implements BillingEvent {
    @Override public String type() { return "billing.bill.generated"; }
    @Override public UUID aggregateId() { return billId; }

    public static BillGenerated of(Bill b) {
      return new BillGenerated(b.getId(), b.getFlatId(), b.getNumber(), b.getPeriod(), b.getDueDate(),
          b.getAmountPaise(), b.getGstPaise(), b.getTotalPaise());
    }
  }

  public record DuesOverdue(UUID flatId, UUID billId, long overduePaise, long daysOverdue) implements BillingEvent {
    @Override public String type() { return "billing.dues.overdue"; }
    @Override public UUID aggregateId() { return billId; }
  }
}
