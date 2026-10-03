package in.societyos.billing.jobs.infrastructure;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import in.societyos.billing.jobs.application.BillingJobs;
import java.time.Duration;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * db-scheduler (ADR-0004) on this service's {@code scheduled_tasks} table; clustered through row
 * locks, so each recurring task runs on one replica at a time.
 *
 * <ul>
 *   <li>{@code billing-daily-dues}: {@code sos.billing.dues-cron} (default 06:00 IST) reminders,
 *       overdue notices and late fees.
 *   <li>{@code billing-payment-reconcile}: every {@code sos.billing.reconcile-every} (15 min).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sos.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

  @Bean
  SchedulerLifecycle billingScheduler(DataSource dataSource, BillingJobs jobs,
      @Value("${sos.billing.dues-cron:0 0 6 * * *}") String duesCron,
      @Value("${sos.billing.reconcile-every:15m}") Duration reconcileEvery,
      @Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    RecurringTask<Void> dues = Tasks.recurring("billing-daily-dues", Schedules.cron(duesCron, ZoneId.of(zone)))
        .execute((instance, ctx) -> jobs.dailyDues());
    RecurringTask<Void> reconcile = Tasks.recurring("billing-payment-reconcile", Schedules.fixedDelay(reconcileEvery))
        .execute((instance, ctx) -> jobs.reconcilePayments());
    Scheduler scheduler = Scheduler.create(dataSource)
        .startTasks(dues, reconcile)
        .pollingInterval(Duration.ofSeconds(30))
        .threads(2)
        .shutdownMaxWait(Duration.ofSeconds(10))
        .build();
    return new SchedulerLifecycle(scheduler);
  }

  /** Starts after the context is ready, stops before the datasource closes. */
  static final class SchedulerLifecycle implements SmartLifecycle {

    private final Scheduler scheduler;
    private volatile boolean running;

    SchedulerLifecycle(Scheduler scheduler) {
      this.scheduler = scheduler;
    }

    @Override
    public void start() {
      scheduler.start();
      running = true;
    }

    @Override
    public void stop() {
      scheduler.stop();
      running = false;
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public int getPhase() {
      return Integer.MAX_VALUE - 200;
    }
  }
}
