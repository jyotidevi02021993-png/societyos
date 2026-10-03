package in.societyos.billing.jobs.application;

import in.societyos.billing.bill.application.DuesService;
import in.societyos.billing.jobs.infrastructure.SocietyScan;
import in.societyos.billing.payment.application.PaymentService;
import in.societyos.billing.platform.core.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Work woken by db-scheduler (ADR-0004). Each job iterates the societies that have work and runs
 * one transaction per society with that society bound, so RLS scopes every statement and one bad
 * society never blocks the others. The bills and payments are the source of truth.
 */
@Service
public class BillingJobs {

  private static final Logger log = LoggerFactory.getLogger(BillingJobs.class);

  private final SocietyScan scan;
  private final DuesService dues;
  private final PaymentService payments;
  private final Clock clock;

  public BillingJobs(SocietyScan scan, DuesService dues, PaymentService payments, Clock clock) {
    this.scan = scan;
    this.dues = dues;
    this.payments = payments;
    this.clock = clock;
  }

  /** Daily: due reminders, overdue notices and late fees after the grace period. */
  public void dailyDues() {
    perSociety(scan.withOpenBills(), "daily dues", society -> {
      DuesService.DailyResult r = dues.runDaily();
      if (r.reminders() + r.overdue() + r.lateFees() > 0) {
        log.info("Dues pass: {} reminders, {} overdue, {} late fees", r.reminders(), r.overdue(), r.lateFees());
      }
    });
  }

  /** Every 15 minutes: gateway orders still pending after 10 minutes are checked with the gateway. */
  public void reconcilePayments() {
    perSociety(scan.withPendingPayments(clock.instant().minus(Duration.ofMinutes(10))), "payment reconcile",
        society -> payments.reconcile());
  }

  private static void perSociety(Iterable<UUID> societies, String job, Consumer<UUID> work) {
    for (UUID society : societies) {
      try {
        TenantContext.runAs(society, () -> work.accept(society));
      } catch (RuntimeException ex) {
        log.warn("{} failed for society {}: {}", job, society, ex.toString());
      }
    }
  }
}
