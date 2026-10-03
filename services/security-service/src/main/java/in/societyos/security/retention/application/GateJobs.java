package in.societyos.security.retention.application;

import in.societyos.security.attendance.application.AttendanceService;
import in.societyos.security.delivery.application.DeliveryService;
import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.entry.application.EntryService;
import in.societyos.security.gatepass.application.GatePassService;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.platform.events.DomainEvents;
import in.societyos.security.retention.domain.RetentionEvents.EntriesPurged;
import in.societyos.security.retention.infrastructure.SocietyScan;
import in.societyos.security.vehicle.application.VehicleMovementService;
import in.societyos.security.visitor.infrastructure.VisitorRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Work woken by db-scheduler (ADR-0004). Each job iterates societies and opens one transaction
 * per society with that society bound, so RLS scopes every statement and one bad society never
 * blocks the others. The domain tables (entry_log.expires_at, gate_pass.valid_to, settings) are the
 * source of truth; the scheduler only decides when to look.
 */
@Service
public class GateJobs {

  private static final Logger log = LoggerFactory.getLogger(GateJobs.class);

  private final SocietyScan scan;
  private final EntryService entries;
  private final GatePassService passes;
  private final DeliveryService deliveries;
  private final AttendanceService attendance;
  private final VehicleMovementService vehicles;
  private final VisitorRepository visitors;
  private final DirectoryService directory;
  private final DomainEvents events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public GateJobs(SocietyScan scan, EntryService entries, GatePassService passes, DeliveryService deliveries,
      AttendanceService attendance, VehicleMovementService vehicles, VisitorRepository visitors,
      DirectoryService directory, DomainEvents events, PlatformTransactionManager txManager, Clock clock) {
    this.scan = scan;
    this.entries = entries;
    this.passes = passes;
    this.deliveries = deliveries;
    this.attendance = attendance;
    this.vehicles = vehicles;
    this.visitors = visitors;
    this.directory = directory;
    this.events = events;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  /** Every few seconds: walk-in requests past the society timeout expire; stale passes free their code. */
  public void expire() {
    Instant now = clock.instant();
    perSociety(scan.withExpiredEntries(now), "entry expiry", s -> {
      int n = entries.expireDue(now);
      if (n > 0) {
        log.info("Expired {} entry request(s)", n);
      }
    });
    perSociety(scan.withExpiredPasses(now), "pass expiry", s -> passes.expireDue(now));
  }

  /** Nightly: gate records older than the society retention period are deleted (DPDP retention). */
  public void purge() {
    Instant now = clock.instant();
    perSociety(scan.known(), "retention purge", society -> tx.executeWithoutResult(status -> {
      int days = directory.settings().visitorRetentionDays();
      Instant before = now.minus(Duration.ofDays(days));
      int d = deliveries.purgeBefore(before);
      int a = attendance.purgeBefore(before);
      int e = entries.purgeBefore(before);
      int v = visitors.purge(society, before);
      int m = vehicles.purgeBefore(before);
      if (d + a + e + v + m > 0) {
        events.publish(new EntriesPurged(society, before, days, e, v, d, a, m));
        log.info("Retention purge before {}: {} entries, {} visitors, {} deliveries, {} staff visits, {} vehicles",
            before, e, v, d, a, m);
      }
    }));
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
