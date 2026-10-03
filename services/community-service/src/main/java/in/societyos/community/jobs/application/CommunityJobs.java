package in.societyos.community.jobs.application;

import in.societyos.community.jobs.infrastructure.DueWorkScan;
import in.societyos.community.notice.application.NoticeService;
import in.societyos.community.platform.core.tenant.TenantContext;
import in.societyos.community.poll.application.PollService;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Woken by db-scheduler (ADR-0004): publishes scheduled notices that are due and closes polls
 * past {@code closesAt}, one society (and one transaction per step) at a time.
 */
@Service
public class CommunityJobs {

  private static final Logger log = LoggerFactory.getLogger(CommunityJobs.class);

  private final DueWorkScan scan;
  private final NoticeService notices;
  private final PollService polls;
  private final Clock clock;

  public CommunityJobs(DueWorkScan scan, NoticeService notices, PollService polls, Clock clock) {
    this.scan = scan;
    this.notices = notices;
    this.polls = polls;
    this.clock = clock;
  }

  public void runDue() {
    for (UUID society : scan.societiesWithDueWork(clock.instant())) {
      try {
        TenantContext.runAs(society, () -> {
          int n = notices.publishDue();
          int p = polls.closeDue();
          if (n + p > 0) {
            log.info("Published {} scheduled notice(s), closed {} poll(s)", n, p);
          }
        });
      } catch (RuntimeException ex) {
        log.warn("Due work failed for society {}: {}", society, ex.toString());
      }
    }
  }
}
