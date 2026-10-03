package in.societyos.security.config;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import in.societyos.security.retention.application.GateJobs;
import java.time.Duration;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * db-scheduler (ADR-0004) on this service's {@code scheduled_tasks} table: clustered through row
 * locks, so each recurring task runs on one replica at a time.
 *
 * <ul>
 *   <li>{@code security-entry-expiry}: every {@code sos.gate.expiry-poll} (default 5 s), expires
 *       walk-in requests past the society {@code gateApprovalTimeoutSeconds} and stale passes.
 *   <li>{@code security-retention-purge}: nightly ({@code sos.gate.retention-cron}, 02:30 IST),
 *       deletes gate records older than the society {@code visitorRetentionDays}.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sos.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

  @Bean
  SchedulerLifecycle gateScheduler(DataSource dataSource, GateJobs jobs,
      @Value("${sos.gate.expiry-poll:5s}") Duration expiryPoll,
      @Value("${sos.gate.retention-cron:0 30 2 * * *}") String retentionCron,
      @Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    RecurringTask<Void> expiry = Tasks.recurring("security-entry-expiry", Schedules.fixedDelay(expiryPoll))
        .execute((instance, ctx) -> jobs.expire());
    RecurringTask<Void> purge = Tasks.recurring("security-retention-purge",
            Schedules.cron(retentionCron, ZoneId.of(zone)))
        .execute((instance, ctx) -> jobs.purge());
    Duration polling = expiryPoll.compareTo(Duration.ofSeconds(10)) < 0 ? expiryPoll : Duration.ofSeconds(10);
    Scheduler scheduler = Scheduler.create(dataSource)
        .startTasks(expiry, purge)
        .pollingInterval(polling)
        .threads(2)
        .shutdownMaxWait(Duration.ofSeconds(10))
        .build();
    return new SchedulerLifecycle(scheduler);
  }

  /** Starts after the context is ready (listeners and web first), stops before the datasource closes. */
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
