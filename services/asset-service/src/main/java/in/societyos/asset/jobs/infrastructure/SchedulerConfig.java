package in.societyos.asset.jobs.infrastructure;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import in.societyos.asset.jobs.application.DailyAssetJobs;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * db-scheduler on this service's Postgres (ADR-0004): clustered through row locks on
 * {@code scheduled_tasks}, so the daily jobs run once across replicas.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sos.asset.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SchedulerConfig {

  @Bean
  RecurringTask<Void> dailyAssetJobsTask(DailyAssetJobs jobs) {
    return Tasks.recurring("asset-daily-jobs", Schedules.cron("0 0/30 * * * *"))
        .execute((instance, ctx) -> jobs.runAll());
  }

  @Bean
  SchedulerLifecycle assetScheduler(DataSource dataSource, RecurringTask<Void> dailyAssetJobsTask) {
    Scheduler scheduler = Scheduler.create(dataSource)
        .startTasks(dailyAssetJobsTask)
        .threads(2)
        .pollingInterval(Duration.ofSeconds(30))
        .build();
    return new SchedulerLifecycle(scheduler);
  }

  /** Starts after the context is ready and stops before the datasource closes. */
  static final class SchedulerLifecycle implements SmartLifecycle {
    private final Scheduler scheduler;
    private volatile boolean running;

    SchedulerLifecycle(Scheduler scheduler) {
      this.scheduler = scheduler;
    }

    @Override public void start() { scheduler.start(); running = true; }
    @Override public void stop() { scheduler.stop(); running = false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 200; }
  }
}
