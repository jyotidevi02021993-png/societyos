package in.societyos.audit.log.application;

import in.societyos.audit.log.infrastructure.AuditLogStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps monthly partitions ahead of time: this month plus the next {@code monthsAhead}. */
@Service
public class PartitionMaintenance {

  private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);
  static final int MONTHS_AHEAD = 3;

  private final AuditLogStore store;
  private final Clock clock;

  public PartitionMaintenance(AuditLogStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional
  public void ensureAhead() {
    LocalDate month = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
    for (int i = 0; i <= MONTHS_AHEAD; i++) {
      String part = store.ensurePartition(month.plusMonths(i));
      if (part == null) {
        log.warn("Partition for {} not created: rows already in audit_log_default", month.plusMonths(i));
      }
    }
  }
}
