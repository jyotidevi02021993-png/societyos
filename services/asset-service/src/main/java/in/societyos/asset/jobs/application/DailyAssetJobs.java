package in.societyos.asset.jobs.application;

import in.societyos.asset.coverage.application.CoverageService;
import in.societyos.asset.platform.core.tenant.Tenant;
import in.societyos.asset.platform.core.tenant.TenantContext;
import in.societyos.asset.pm.application.PmTaskService;
import in.societyos.asset.reference.infrastructure.SocietyDirectory;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The daily asset jobs, run per society in its own time zone (ADR-0004 {@code PerSocietyTask}):
 * PM task generation, overdue marking and warranty/AMC expiry alerts. Every step is idempotent,
 * so the scheduler wakes this up every 30 minutes and a society is processed once its local
 * time has passed {@code sos.asset.jobs.run-after} (00:30).
 */
@Service
public class DailyAssetJobs {

  private static final Logger log = LoggerFactory.getLogger(DailyAssetJobs.class);

  public record Result(int pmTasksGenerated, int pmTasksOverdue, int expiryAlerts) {}

  private final SocietyDirectory societies;
  private final PmTaskService pmTasks;
  private final CoverageService coverage;
  private final Clock clock;
  private final LocalTime runAfter;

  public DailyAssetJobs(SocietyDirectory societies, PmTaskService pmTasks, CoverageService coverage, Clock clock,
      @Value("${sos.asset.jobs.run-after:00:30}") String runAfter) {
    this.societies = societies;
    this.pmTasks = pmTasks;
    this.coverage = coverage;
    this.clock = clock;
    this.runAfter = LocalTime.parse(runAfter);
  }

  /** Scheduler entry point: every known society whose local time is past {@code runAfter}. */
  public void runAll() {
    for (SocietyDirectory.SocietyZone s : societies.all()) {
      ZonedDateTime local = ZonedDateTime.now(clock.withZone(s.zone()));
      if (local.toLocalTime().isBefore(runAfter)) {
        continue;
      }
      try {
        runFor(s.societyId(), local.toLocalDate());
      } catch (RuntimeException e) {
        log.error("Daily asset jobs failed for society {}", s.societyId(), e);
      }
    }
  }

  /** One society, one local date. Each step commits on its own. */
  public Result runFor(UUID societyId, LocalDate today) {
    MDC.put("societyId", societyId.toString());
    try {
      int[] r = new int[3];
      TenantContext.runAs(Tenant.system(societyId), () -> {
        r[0] = pmTasks.generateCalendarTasks(today).size();
        r[1] = pmTasks.markOverdue(today);
        r[2] = coverage.sendExpiryAlerts(today);
      });
      if (r[0] + r[1] + r[2] > 0) {
        log.info("Society {} on {}: {} PM tasks generated, {} overdue, {} expiry alerts", societyId, today, r[0], r[1], r[2]);
      }
      return new Result(r[0], r[1], r[2]);
    } finally {
      MDC.remove("societyId");
    }
  }
}
