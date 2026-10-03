package in.societyos.billing.bill.application;

import in.societyos.billing.bill.domain.Bill;
import in.societyos.billing.bill.domain.BillEvents;
import in.societyos.billing.bill.domain.DuesRules;
import in.societyos.billing.bill.infrastructure.BillRepository;
import in.societyos.billing.common.BillingClock;
import in.societyos.billing.common.Paise;
import in.societyos.billing.ledger.application.LedgerService;
import in.societyos.billing.ledger.domain.Journal;
import in.societyos.billing.ledger.domain.Journal.Account;
import in.societyos.billing.notification.application.BillingNotifier;
import in.societyos.billing.platform.events.DomainEvents;
import in.societyos.billing.roster.application.RosterService;
import in.societyos.billing.roster.domain.BillingSettings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily dues pass for the bound society (woken by db-scheduler): due reminders
 * ({@code reminderDaysBefore} days ahead), overdue notices ({@code billing.dues.overdue}, the day
 * after the due date) and the one-time late fee once {@code lateFeeGraceDays} have passed. Each
 * step is recorded on the bill, so running the pass twice in a day changes nothing.
 */
@Service
public class DuesService {

  public record DailyResult(int reminders, int overdue, int lateFees) {}

  private final BillRepository bills;
  private final RosterService roster;
  private final LedgerService ledger;
  private final DomainEvents events;
  private final BillingNotifier notifier;
  private final BillingClock clock;

  public DuesService(BillRepository bills, RosterService roster, LedgerService ledger, DomainEvents events,
      BillingNotifier notifier, BillingClock clock) {
    this.bills = bills;
    this.roster = roster;
    this.ledger = ledger;
    this.events = events;
    this.notifier = notifier;
    this.clock = clock;
  }

  @Transactional
  public DailyResult runDaily() {
    LocalDate today = clock.today();
    Instant now = clock.now();
    BillingSettings s = roster.settings();
    int reminders = 0;
    int overdue = 0;
    int fees = 0;
    for (Bill bill : bills.openDueBy(today.plusDays(s.getReminderDaysBefore()))) {
      LocalDate due = bill.getDueDate();
      if (!DuesRules.isOverdue(due, today)) {
        if (bill.getReminderSentAt() == null) {
          notifier.toFlat(bill.getFlatId(), BillingNotifier.DUE_REMINDER, Map.of(
              "flatLabel", bill.getFlatLabel(), "billNumber", bill.getNumber(),
              "amount", Paise.rupees(bill.getBalancePaise()), "dueDate", due.toString(),
              "daysLeft", Long.toString(ChronoUnit.DAYS.between(today, due))), "reminder:" + bill.getId(), false);
          bill.reminderSent(now);
          reminders++;
        }
        continue;
      }
      long days = DuesRules.daysOverdue(due, today);
      if (bill.getOverdueNotifiedAt() == null) {
        events.publish(new BillEvents.DuesOverdue(bill.getFlatId(), bill.getId(), bill.getBalancePaise(), days));
        notifier.toFlat(bill.getFlatId(), BillingNotifier.OVERDUE, Map.of(
            "flatLabel", bill.getFlatLabel(), "billNumber", bill.getNumber(),
            "amount", Paise.rupees(bill.getBalancePaise()), "dueDate", due.toString(),
            "graceDays", Integer.toString(s.getLateFeeGraceDays())), "overdue:" + bill.getId(), true);
        bill.overdueNotified(now);
        overdue++;
      }
      if (bill.getLateFeeAppliedAt() == null && DuesRules.lateFeeDue(due, s.getLateFeeGraceDays(), today)) {
        long fee = DuesRules.lateFee(s.getLateFeeKind(), s.getLateFeeValue(), bill.getBalancePaise());
        if (fee > 0) {
          bill.applyLateFee(fee, now);
          ledger.post(new Journal("LATE_FEE", bill.getId(), "Late fee on " + bill.getNumber(), today)
              .debit(Account.MEMBER_RECEIVABLE, bill.getFlatId(), fee)
              .credit(Account.LATE_FEE_INCOME, fee));
          notifier.toFlat(bill.getFlatId(), BillingNotifier.LATE_FEE, Map.of(
              "flatLabel", bill.getFlatLabel(), "billNumber", bill.getNumber(),
              "lateFee", Paise.rupees(fee), "amount", Paise.rupees(bill.getBalancePaise())),
              "latefee:" + bill.getId(), true);
          fees++;
        }
      }
      bills.save(bill);
    }
    return new DailyResult(reminders, overdue, fees);
  }
}
