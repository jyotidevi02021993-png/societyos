package in.societyos.society.imports.application;

import in.societyos.society.imports.application.ImportPlan.NewFlat;
import in.societyos.society.imports.application.ImportPlan.NewResident;
import in.societyos.society.imports.application.ImportPlan.NewTower;
import in.societyos.society.imports.domain.ImportJob;
import in.societyos.society.imports.domain.ImportReport;
import in.societyos.society.imports.domain.ImportReport.Counts;
import in.societyos.society.imports.domain.ImportReport.RowError;
import in.societyos.society.imports.domain.ImportSheets;
import in.societyos.society.imports.infrastructure.ExcelWorkbooks;
import in.societyos.society.imports.infrastructure.ImportJobRepository;
import in.societyos.society.member.application.MemberService;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.core.tenant.Tenant;
import in.societyos.society.platform.core.tenant.TenantContext;
import in.societyos.society.society.application.FlatService;
import in.societyos.society.society.application.TowerService;
import in.societyos.society.society.domain.Flat;
import in.societyos.society.society.domain.Tower;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Excel bulk onboarding. The upload is parsed at once (a broken file fails the request), then
 * checked and applied in the background as the uploader:
 *
 * <ol>
 *   <li>every row is validated first; any error → VALIDATION_FAILED and nothing is written;
 *   <li>towers and flats are created in one transaction (all or nothing);
 *   <li>residents are added one by one (each needs identity-service); a failing row is reported
 *       and the rest continue → COMPLETED_WITH_ERRORS.
 * </ol>
 *
 * A dry run stops after step 1 and reports what would be created.
 */
@Service
public class ImportService {

  private static final Logger log = LoggerFactory.getLogger(ImportService.class);
  static final int MAX_BYTES = 5 * 1024 * 1024;
  /** A job still open after this long was cut off by a restart. */
  static final Duration STALE_AFTER = Duration.ofMinutes(30);

  public record JobView(ImportJob job, ImportReport report) {}

  private final ImportJobRepository jobs;
  private final ExcelWorkbooks workbooks;
  private final TowerService towers;
  private final FlatService flats;
  private final MemberService members;
  private final JsonMapper json;
  private final TransactionTemplate tx;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public ImportService(ImportJobRepository jobs, ExcelWorkbooks workbooks, TowerService towers, FlatService flats,
      MemberService members, JsonMapper json, PlatformTransactionManager txManager) {
    this.jobs = jobs;
    this.workbooks = workbooks;
    this.towers = towers;
    this.flats = flats;
    this.members = members;
    this.json = json;
    this.tx = new TransactionTemplate(txManager);
  }

  public JobView start(String fileName, byte[] content, boolean dryRun) {
    if (content == null || content.length == 0) {
      throw ProblemException.badRequest("IMPORT_EMPTY", "The file is empty");
    }
    if (content.length > MAX_BYTES) {
      throw ProblemException.badRequest("IMPORT_TOO_LARGE", "The file is larger than 5 MB");
    }
    ImportSheets sheets = workbooks.read(content);
    String name = fileName == null || fileName.isBlank() ? "import.xlsx" : fileName.trim();
    ImportJob job = tx.execute(s -> jobs.save(new ImportJob(name, dryRun)));
    Tenant uploader = TenantContext.current();
    executor.submit(() -> TenantContext.runAs(uploader, () -> run(job.getId(), sheets)));
    return new JobView(job, null);
  }

  public JobView get(UUID id) {
    ImportJob job = tx.execute(s -> expireIfStale(jobs.findById(id).orElseThrow(() -> ProblemException.notFound("import", id))));
    return view(job);
  }

  public List<JobView> recent() {
    return tx.execute(s -> jobs.findTop50ByOrderByCreatedAtDesc().stream().map(this::expireIfStale).toList())
        .stream().map(this::view).toList();
  }

  public byte[] template() {
    return workbooks.template();
  }

  /** Runs in the background with the uploader bound as tenant. */
  void run(UUID jobId, ImportSheets sheets) {
    try {
      tx.executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().start());
      ImportPlan plan = tx.execute(s -> ImportValidator.validate(sheets, existing()));
      ImportJob.Status status;
      ImportReport report;
      if (!plan.valid()) {
        status = ImportJob.Status.VALIDATION_FAILED;
        report = new ImportReport(
            new Counts(0, plan.towersSkipped(), failed(plan, ImportSheets.TOWERS)),
            new Counts(0, plan.flatsSkipped(), failed(plan, ImportSheets.FLATS)),
            new Counts(0, 0, failed(plan, ImportSheets.RESIDENTS)),
            plan.errors());
      } else if (jobs.findById(jobId).map(ImportJob::isDryRun).orElse(true)) {
        status = ImportJob.Status.COMPLETED;
        report = new ImportReport(new Counts(plan.towers().size(), plan.towersSkipped(), 0),
            new Counts(plan.flats().size(), plan.flatsSkipped(), 0),
            new Counts(plan.residents().size(), 0, 0), List.of());
      } else {
        report = apply(plan);
        status = report.errors().isEmpty() ? ImportJob.Status.COMPLETED : ImportJob.Status.COMPLETED_WITH_ERRORS;
      }
      finish(jobId, status, report);
    } catch (RuntimeException e) {
      log.error("Import {} failed", jobId, e);
      finish(jobId, ImportJob.Status.FAILED, ImportReport.failure(
          e instanceof ProblemException p ? p.getMessage() : "The import stopped unexpectedly; nothing more was changed"));
    }
  }

  private ImportReport apply(ImportPlan plan) {
    tx.executeWithoutResult(s -> {
      for (NewTower t : plan.towers()) {
        towers.create(t.name(), t.code(), t.floors());
      }
      for (NewFlat f : plan.flats()) {
        Tower tower = towers.findByCode(f.towerCode()).orElseThrow();
        flats.create(tower.getId(), f.number(), f.floor(), f.areaSqft(), f.flatType());
      }
    });

    List<RowError> errors = new ArrayList<>();
    int added = 0;
    for (NewResident r : plan.residents()) {
      try {
        UUID flatId = flats.findByLabel(r.flatLabel()).map(Flat::getId).orElseThrow(
            () -> ProblemException.notFound("flat", r.flatLabel()));
        members.add(new MemberService.NewMember(flatId, r.phone(), r.name(), r.kind(), r.fromDate(), r.primary()));
        added++;
      } catch (ProblemException e) {
        errors.add(new RowError(ImportSheets.RESIDENTS, r.row(), null, e.getMessage()));
      }
    }
    return new ImportReport(new Counts(plan.towers().size(), plan.towersSkipped(), 0),
        new Counts(plan.flats().size(), plan.flatsSkipped(), 0),
        new Counts(added, 0, errors.size()), errors);
  }

  private ImportValidator.Existing existing() {
    Map<String, Integer> towerFloors = towers.list().stream()
        .collect(Collectors.toMap(t -> t.getCode().toUpperCase(Locale.ROOT), Tower::getFloorsCount, (a, b) -> a));
    Set<String> labels = flats.list(null).stream()
        .map(f -> f.getLabel().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    return new ImportValidator.Existing(towerFloors, labels);
  }

  private void finish(UUID jobId, ImportJob.Status status, ImportReport report) {
    try {
      tx.executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().finish(status, json.writeValueAsString(report)));
    } catch (RuntimeException e) {
      log.error("Could not record the result of import {}", jobId, e);
    }
  }

  private ImportJob expireIfStale(ImportJob job) {
    if (job.isOpen() && job.getUpdatedAt() != null && job.getUpdatedAt().isBefore(Instant.now().minus(STALE_AFTER))) {
      job.finish(ImportJob.Status.FAILED, json.writeValueAsString(
          ImportReport.failure("The import was interrupted (service restart); upload the file again")));
      return jobs.save(job);
    }
    return job;
  }

  private JobView view(ImportJob job) {
    ImportReport report = job.getReportJson() == null ? null : json.readValue(job.getReportJson(), ImportReport.class);
    return new JobView(job, report);
  }

  private static int failed(ImportPlan plan, String sheet) {
    return (int) plan.errors().stream().filter(e -> sheet.equals(e.sheet())).map(RowError::row).distinct().count();
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
