package in.societyos.workflow.scheduling.infrastructure;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import in.societyos.workflow.platform.core.tenant.TenantContext;
import in.societyos.workflow.scheduling.application.Timers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link Timers} on db-scheduler (table {@code scheduled_tasks}, clustered through row locks).
 * Task data is {@code "<societyId>:<rowId>"}; one task instance per (row, wake time).
 */
@Component
class DbSchedulerTimers implements Timers, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(DbSchedulerTimers.class);

  private final DataSource dataSource;
  private final ObjectProvider<TimerHandler> handlers;
  private final Duration pollingInterval;
  private final boolean enabled;
  private final Map<String, OneTimeTask<String>> tasks = new ConcurrentHashMap<>();
  private volatile Scheduler scheduler;

  DbSchedulerTimers(DataSource dataSource, ObjectProvider<TimerHandler> handlers,
      @Value("${sos.scheduler.polling-interval:5s}") Duration pollingInterval,
      @Value("${sos.scheduler.enabled:true}") boolean enabled) {
    this.dataSource = dataSource;
    this.handlers = handlers;
    this.pollingInterval = pollingInterval;
    this.enabled = enabled;
  }

  @Override
  public void wakeAt(String taskName, UUID societyId, UUID id, Instant at) {
    Runnable schedule = () -> {
      OneTimeTask<String> task = tasks.get(taskName);
      if (task == null || scheduler == null) {
        log.warn("Timer {} for {} not scheduled: scheduler not running", taskName, id);
        return;
      }
      scheduler.scheduleIfNotExists(task.instance(id + "@" + at.toEpochMilli(), societyId + ":" + id), at);
    };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          schedule.run();
        }
      });
    } else {
      schedule.run();
    }
  }

  @Override
  public void start() {
    List<Task<?>> all = new ArrayList<>();
    handlers.orderedStream().forEach(handler -> {
      OneTimeTask<String> task = Tasks.oneTime(handler.taskName(), String.class)
          .execute((instance, ctx) -> run(handler, instance.getData()));
      tasks.put(handler.taskName(), task);
      all.add(task);
    });
    if (!enabled) {
      return;
    }
    scheduler = Scheduler.create(dataSource, all).pollingInterval(pollingInterval).threads(4)
        .enableImmediateExecution().build();
    scheduler.start();
    log.info("db-scheduler started with tasks {}", tasks.keySet());
  }

  private static void run(TimerHandler handler, String data) {
    int sep = data.indexOf(':');
    UUID societyId = UUID.fromString(data.substring(0, sep));
    UUID id = UUID.fromString(data.substring(sep + 1));
    TenantContext.runAs(societyId, () -> handler.wake(id));
  }

  @Override
  public void stop() {
    Scheduler s = scheduler;
    scheduler = null;
    if (s != null) {
      s.stop();
    }
  }

  @Override
  public boolean isRunning() {
    return scheduler != null;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 200;
  }
}
