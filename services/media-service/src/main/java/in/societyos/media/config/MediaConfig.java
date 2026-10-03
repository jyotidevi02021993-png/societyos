package in.societyos.media.config;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import in.societyos.media.media.application.MediaJobs;
import in.societyos.media.media.application.MediaProperties;
import in.societyos.media.media.application.Thumbnailer;
import in.societyos.media.media.application.VirusScanner;
import in.societyos.media.media.infrastructure.ImageIoThumbnailer;
import in.societyos.media.media.infrastructure.S3ObjectStorage;
import in.societyos.media.media.infrastructure.StubVirusScanner;
import java.time.Duration;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Storage, scanner and thumbnailer wiring, plus db-scheduler (ADR-0004) for processing and retention. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {

  @Bean(destroyMethod = "close")
  S3ObjectStorage objectStorage(MediaProperties props) {
    S3ObjectStorage storage = new S3ObjectStorage(props);
    if (props.isCreateBucket()) {
      storage.ensureBucket();
    }
    return storage;
  }

  @Bean
  @ConditionalOnMissingBean(VirusScanner.class)
  VirusScanner virusScanner() {
    return new StubVirusScanner();
  }

  @Bean
  Thumbnailer thumbnailer() {
    return new ImageIoThumbnailer();
  }

  @Bean
  @ConditionalOnProperty(name = "sos.scheduler.enabled", havingValue = "true", matchIfMissing = true)
  SchedulerLifecycle mediaScheduler(DataSource dataSource, MediaJobs jobs, MediaProperties props,
      @Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    RecurringTask<Void> process = Tasks.recurring("media-process", Schedules.fixedDelay(props.getProcessPoll()))
        .execute((instance, ctx) -> jobs.process());
    RecurringTask<Void> purge = Tasks.recurring("media-retention-purge",
            Schedules.cron(props.getRetentionCron(), ZoneId.of(zone)))
        .execute((instance, ctx) -> jobs.purge());
    Duration poll = props.getProcessPoll().compareTo(Duration.ofSeconds(10)) < 0
        ? props.getProcessPoll() : Duration.ofSeconds(10);
    Scheduler scheduler = Scheduler.create(dataSource)
        .startTasks(process, purge)
        .pollingInterval(poll)
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
