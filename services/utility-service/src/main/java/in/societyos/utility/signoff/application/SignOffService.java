package in.societyos.utility.signoff.application;

import in.societyos.utility.checklist.application.ChecklistService;
import in.societyos.utility.checklist.domain.ChecklistRun;
import in.societyos.utility.checklist.domain.ChecklistTemplate;
import in.societyos.utility.meter.infrastructure.ReadingRepository;
import in.societyos.utility.platform.core.error.ProblemException;
import in.societyos.utility.platform.core.tenant.TenantContext;
import in.societyos.utility.platform.events.DomainEvents;
import in.societyos.utility.reference.application.ReferenceData;
import in.societyos.utility.signoff.domain.ManagerSignOff;
import in.societyos.utility.signoff.domain.SignOffCompleted;
import in.societyos.utility.signoff.infrastructure.ManagerSignOffRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The manager's day: what was read, what was checked, what failed, what is still pending; and
 * the daily sign-off that freezes that summary.
 */
@Service
public class SignOffService {

  public record DaySummary(LocalDate date, long readings, long anomalies, int runsCompleted, int runsInProgress,
      int failedItems, List<String> pendingChecklists, UUID signoffId) {}

  private final ManagerSignOffRepository signoffs;
  private final ReadingRepository readings;
  private final ChecklistService checklists;
  private final ReferenceData reference;
  private final DomainEvents events;
  private final JsonMapper json;

  public SignOffService(ManagerSignOffRepository signoffs, ReadingRepository readings, ChecklistService checklists,
      ReferenceData reference, DomainEvents events, JsonMapper json) {
    this.signoffs = signoffs;
    this.readings = readings;
    this.checklists = checklists;
    this.reference = reference;
    this.events = events;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public DaySummary summary(LocalDate date) {
    LocalDate day = date == null ? reference.today() : date;
    Instant from = reference.startOf(day);
    Instant to = reference.startOf(day.plusDays(1));
    List<ChecklistRun> runs = checklists.runsOn(day);
    Set<UUID> completedTemplates = runs.stream().filter(ChecklistRun::isCompleted)
        .map(ChecklistRun::getTemplateId).collect(Collectors.toSet());
    List<String> pending = checklists.dailyTemplates().stream()
        .filter(t -> !completedTemplates.contains(t.getId())).map(ChecklistTemplate::getName).toList();
    return new DaySummary(day,
        readings.countByAtGreaterThanEqualAndAtLessThan(from, to),
        readings.countByAtGreaterThanEqualAndAtLessThanAndAnomalyTrue(from, to),
        (int) runs.stream().filter(ChecklistRun::isCompleted).count(),
        (int) runs.stream().filter(r -> !r.isCompleted()).count(),
        runs.stream().mapToInt(ChecklistRun::getFailedCount).sum(),
        pending,
        signoffs.findBySignDate(day).map(ManagerSignOff::getId).orElse(null));
  }

  /**
   * Signs off a day (today or earlier). Pending daily checklists block the sign-off unless the
   * manager acknowledges them; the summary is stored with the sign-off.
   */
  @Transactional
  public ManagerSignOff signOff(LocalDate date, String remarks, boolean acknowledgePending) {
    LocalDate day = date == null ? reference.today() : date;
    if (day.isAfter(reference.today())) {
      throw ProblemException.badRequest("INVALID_SIGNOFF_DATE", "A future day cannot be signed off");
    }
    if (signoffs.findBySignDate(day).isPresent()) {
      throw ProblemException.conflict("ALREADY_SIGNED_OFF", day + " is already signed off");
    }
    DaySummary summary = summary(day);
    if (!summary.pendingChecklists().isEmpty() && !acknowledgePending) {
      throw ProblemException.unprocessable("SIGNOFF_PENDING_CHECKLISTS",
          "Checklists not done: " + String.join(", ", summary.pendingChecklists())
              + ". Acknowledge them to sign off anyway.");
    }
    if (!summary.pendingChecklists().isEmpty() && (remarks == null || remarks.isBlank())) {
      throw ProblemException.badRequest("REMARKS_REQUIRED", "Explain the pending checklists in the remarks");
    }
    UUID manager = TenantContext.userId()
        .orElseThrow(() -> ProblemException.forbidden("USER_REQUIRED", "Only a manager can sign off"));
    ManagerSignOff saved;
    try {
      saved = signoffs.saveAndFlush(new ManagerSignOff(day, manager, remarks == null ? null : remarks.trim(),
          json.writeValueAsString(summary), reference.now()));
    } catch (DataIntegrityViolationException e) {
      throw ProblemException.conflict("ALREADY_SIGNED_OFF", day + " is already signed off");
    }
    events.publish(new SignOffCompleted(saved.getId(), day, manager));
    return saved;
  }

  @Transactional(readOnly = true)
  public List<ManagerSignOff> list(LocalDate from, LocalDate to) {
    LocalDate t = to == null ? reference.today() : to;
    LocalDate f = from == null ? t.minusDays(30) : from;
    return signoffs.findBySignDateBetweenOrderBySignDateDesc(f, t);
  }
}
