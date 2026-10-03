package in.societyos.utility.checklist.application;

import in.societyos.utility.checklist.domain.ChecklistEvents;
import in.societyos.utility.checklist.domain.ChecklistResponse;
import in.societyos.utility.checklist.domain.ChecklistRules;
import in.societyos.utility.checklist.domain.ChecklistRun;
import in.societyos.utility.checklist.domain.ChecklistTemplate;
import in.societyos.utility.checklist.infrastructure.ChecklistResponseRepository;
import in.societyos.utility.checklist.infrastructure.ChecklistRunRepository;
import in.societyos.utility.checklist.infrastructure.ChecklistTemplateRepository;
import in.societyos.utility.common.Alerts;
import in.societyos.utility.platform.core.error.ProblemException;
import in.societyos.utility.platform.core.tenant.TenantContext;
import in.societyos.utility.platform.events.DomainEvents;
import in.societyos.utility.reference.application.ReferenceData;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Checklist templates and daily runs (WTP/STP rounds, DG test, fire panel, lift checks, …). */
@Service
public class ChecklistService {

  private static final TypeReference<List<ChecklistRules.Item>> ITEMS = new TypeReference<>() {};

  public record TemplateInput(String code, String name, String system, String frequency, UUID assetId,
      UUID locationId, List<ChecklistRules.Item> items) {}

  public record TemplateView(ChecklistTemplate template, List<ChecklistRules.Item> items) {}

  public record RunView(ChecklistRun run, List<ChecklistRules.Item> items, List<ChecklistResponse> responses) {}

  private final ChecklistTemplateRepository templates;
  private final ChecklistRunRepository runs;
  private final ChecklistResponseRepository responses;
  private final ReferenceData reference;
  private final DomainEvents events;
  private final Alerts alerts;
  private final JsonMapper json;

  public ChecklistService(ChecklistTemplateRepository templates, ChecklistRunRepository runs,
      ChecklistResponseRepository responses, ReferenceData reference, DomainEvents events, Alerts alerts,
      JsonMapper json) {
    this.templates = templates;
    this.runs = runs;
    this.responses = responses;
    this.reference = reference;
    this.events = events;
    this.alerts = alerts;
    this.json = json;
  }

  // --- templates --------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<TemplateView> templates() {
    return templates.findAllByOrderBySystemAscNameAsc().stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  public TemplateView template(UUID id) {
    return view(requireTemplate(id));
  }

  @Transactional
  public TemplateView createTemplate(TemplateInput in) {
    if (in.code() == null || in.code().isBlank()) {
      throw ProblemException.badRequest("CODE_REQUIRED", "code is required");
    }
    String code = in.code().trim().toUpperCase(Locale.ROOT);
    if (templates.existsByCodeIgnoreCase(code)) {
      throw ProblemException.conflict("CHECKLIST_CODE_EXISTS", "Checklist " + code + " already exists");
    }
    try {
      List<ChecklistRules.Item> items = ChecklistRules.validateItems(in.items());
      return view(templates.save(new ChecklistTemplate(code, in.name().trim(), upper(in.system()),
          upper(in.frequency()), in.assetId(), in.locationId(), json.writeValueAsString(items))));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_CHECKLIST", e.getMessage());
    }
  }

  @Transactional
  public TemplateView updateTemplate(UUID id, TemplateInput in) {
    ChecklistTemplate t = requireTemplate(id);
    try {
      List<ChecklistRules.Item> items = ChecklistRules.validateItems(in.items());
      t.update(in.name().trim(), upper(in.system()), upper(in.frequency()), in.assetId(), in.locationId(),
          json.writeValueAsString(items));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_CHECKLIST", e.getMessage());
    }
    return view(templates.save(t));
  }

  @Transactional
  public TemplateView setActive(UUID id, boolean active) {
    ChecklistTemplate t = requireTemplate(id);
    t.setActive(active);
    return view(templates.save(t));
  }

  // --- runs -------------------------------------------------------------------------------

  /** Starts (or returns the existing) run of a template for a date and shift. */
  @Transactional
  public RunView start(UUID templateId, LocalDate date, String shift) {
    ChecklistTemplate t = requireTemplate(templateId);
    if (!t.isActive()) {
      throw ProblemException.unprocessable("CHECKLIST_INACTIVE", "Checklist " + t.getCode() + " is not active");
    }
    LocalDate day = date == null ? reference.today() : date;
    if (day.isAfter(reference.today())) {
      throw ProblemException.badRequest("INVALID_RUN_DATE", "A checklist cannot be run for a future date");
    }
    String s = shift == null || shift.isBlank() ? "GENERAL" : upper(shift);
    if (!ChecklistRun.SHIFTS.contains(s)) {
      throw ProblemException.badRequest("INVALID_SHIFT", "shift must be one of " + ChecklistRun.SHIFTS);
    }
    ChecklistRun run = runs.findByTemplateIdAndRunDateAndShift(templateId, day, s)
        .orElseGet(() -> runs.save(new ChecklistRun(t, day, s, TenantContext.userId().orElse(null), reference.now())));
    return runView(run);
  }

  /** Submits all answers and completes the run; failed items are handed to ticket-service. */
  @Transactional
  public RunView submit(UUID runId, List<ChecklistRules.Answer> answers, String remarks) {
    ChecklistRun run = requireRun(runId);
    if (run.isCompleted()) {
      throw ProblemException.unprocessable("CHECKLIST_RUN_COMPLETED", "This checklist run is already completed");
    }
    ChecklistTemplate t = requireTemplate(run.getTemplateId());
    ChecklistRules.Evaluation eval;
    try {
      eval = ChecklistRules.evaluate(items(t), answers);
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_CHECKLIST_ANSWER", e.getMessage());
    }
    eval.answers().forEach(s -> responses.save(new ChecklistResponse(run.getId(), s)));
    run.complete(TenantContext.userId().orElse(null), reference.now(), eval.ok(), eval.failed(), eval.na(),
        remarks == null || remarks.isBlank() ? null : remarks.trim());
    runs.save(run);
    events.publish(new ChecklistEvents.ChecklistCompleted(run.getId(), t.getId(), t.getName(), run.getAssetId(),
        run.getLocationId(), eval.ok(), eval.failed()));
    for (ChecklistRules.Scored f : eval.failures()) {
      events.publish(new ChecklistEvents.ChecklistItemFailed(run.getId(), f.item().code(), f.item().label(),
          run.getAssetId(), run.getLocationId(), f.note()));
    }
    if (eval.failed() > 0) {
      alerts.notifyManagers("utility.checklist.item_failed", Map.of("checklist", t.getName(),
          "failedCount", eval.failed(), "date", run.getRunDate()), "NORMAL", "checklist-failed:" + run.getId());
    }
    return runView(run);
  }

  @Transactional(readOnly = true)
  public RunView run(UUID id) {
    return runView(requireRun(id));
  }

  @Transactional(readOnly = true)
  public List<ChecklistRun> runsOn(LocalDate date) {
    return runs.findByRunDateOrderByStartedAtAsc(date == null ? reference.today() : date);
  }

  /** Active daily / per-shift templates, for the day summary. */
  @Transactional(readOnly = true)
  public List<ChecklistTemplate> dailyTemplates() {
    return templates.findByActiveTrueAndFrequencyInOrderByNameAsc(List.of("DAILY", "PER_SHIFT"));
  }

  public List<ChecklistRules.Item> items(ChecklistTemplate t) {
    List<ChecklistRules.Item> items = json.readValue(t.getItemsJson(), ITEMS);
    return items == null ? List.of() : items;
  }

  private RunView runView(ChecklistRun run) {
    ChecklistTemplate t = templates.findById(run.getTemplateId()).orElse(null);
    return new RunView(run, t == null ? List.of() : items(t), responses.findByRunIdOrderByCreatedAtAsc(run.getId()));
  }

  private TemplateView view(ChecklistTemplate t) {
    return new TemplateView(t, items(t));
  }

  private ChecklistTemplate requireTemplate(UUID id) {
    return templates.findById(id).orElseThrow(() -> ProblemException.notFound("checklist_template", id));
  }

  private ChecklistRun requireRun(UUID id) {
    return runs.findById(id).orElseThrow(() -> ProblemException.notFound("checklist_run", id));
  }

  private static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
  }
}
