package in.societyos.community.config;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import in.societyos.community.jobs.application.CommunityJobs;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * db-scheduler (ADR-0004) on {@code scheduled_tasks}: {@code community-due-work} runs every
 * {@code sos.community.due-poll} (default 30 s) on one replica at a time.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sos.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

  @Bean
  SchedulerLifecycle communityScheduler(DataSource dataSource, CommunityJobs jobs,
      @Value("${sos.community.due-poll:30s}") Duration duePoll) {
    RecurringTask<Void> due = Tasks.recurring("community-due-work", Schedules.fixedDelay(duePoll))
        .execute((instance, ctx) -> jobs.runDue());
    Scheduler scheduler = Scheduler.create(dataSource)
        .startTasks(due)
        .pollingInterval(duePoll.compareTo(Duration.ofSeconds(10)) < 0 ? duePoll : Duration.ofSeconds(10))
        .threads(1)
        .shutdownMaxWait(Duration.ofSeconds(10))
        .build();
    return new SchedulerLifecycle(scheduler);
  }

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
