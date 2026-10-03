package in.societyos.utility.checklist.api;

import in.societyos.utility.checklist.application.ChecklistService;
import in.societyos.utility.checklist.domain.ChecklistResponse;
import in.societyos.utility.checklist.domain.ChecklistRules;
import in.societyos.utility.checklist.domain.ChecklistRun;
import in.societyos.utility.checklist.domain.ChecklistTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/checklist-templates} and {@code /v1/checklist-runs}. */
@RestController
public class ChecklistController {

  static final String VIEW = "@perm.hasAny('checklist:execute', 'checklist:manage', 'dashboard:view', 'signoff:daily')";

  private final ChecklistService checklists;

  public ChecklistController(ChecklistService checklists) {
    this.checklists = checklists;
  }

  public record TemplateRequest(@Size(max = 40) String code, @NotBlank @Size(max = 120) String name,
      @NotBlank String system, @NotBlank String frequency, UUID assetId, UUID locationId,
      @NotEmpty @Size(max = 100) List<ChecklistRules.Item> items) {
    ChecklistService.TemplateInput toInput() {
      return new ChecklistService.TemplateInput(code, name, system, frequency, assetId, locationId, items);
    }
  }

  public record TemplateResponse(UUID id, String code, String name, String system, String frequency, UUID assetId,
      UUID locationId, List<ChecklistRules.Item> items, boolean active) {
    static TemplateResponse from(ChecklistService.TemplateView v) {
      ChecklistTemplate t = v.template();
      return new TemplateResponse(t.getId(), t.getCode(), t.getName(), t.getSystem(), t.getFrequency(), t.getAssetId(),
          t.getLocationId(), v.items(), t.isActive());
    }
  }

  public record StartRunRequest(@NotNull UUID templateId, LocalDate date, String shift) {}

  public record SubmitRequest(@NotNull List<ChecklistRules.Answer> answers, @Size(max = 2000) String remarks) {}

  public record ResponseLine(String itemCode, String itemLabel, String result, BigDecimal value, String textValue,
      String note, UUID photoMediaId) {
    static ResponseLine from(ChecklistResponse r) {
      return new ResponseLine(r.getItemCode(), r.getItemLabel(), r.getResult(), r.getValue(), r.getTextValue(),
          r.getNote(), r.getPhotoMediaId());
    }
  }

  public record RunSummary(UUID id, UUID templateId, String templateName, LocalDate runDate, String shift,
      UUID assetId, UUID locationId, String status, UUID startedBy, Instant startedAt, UUID completedBy,
      Instant completedAt, int okCount, int failedCount, int naCount, String remarks) {
    static RunSummary from(ChecklistRun r) {
      return new RunSummary(r.getId(), r.getTemplateId(), r.getTemplateName(), r.getRunDate(), r.getShift(),
          r.getAssetId(), r.getLocationId(), r.getStatus(), r.getStartedBy(), r.getStartedAt(), r.getCompletedBy(),
          r.getCompletedAt(), r.getOkCount(), r.getFailedCount(), r.getNaCount(), r.getRemarks());
    }
  }

  public record RunResponse(RunSummary run, List<ChecklistRules.Item> items, List<ResponseLine> responses) {
    static RunResponse from(ChecklistService.RunView v) {
      return new RunResponse(RunSummary.from(v.run()), v.items(),
          v.responses().stream().map(ResponseLine::from).toList());
    }
  }

  // --- templates --------------------------------------------------------------------------

  @GetMapping("/v1/checklist-templates")
  @PreAuthorize(VIEW)
  public List<TemplateResponse> templates() {
    return checklists.templates().stream().map(TemplateResponse::from).toList();
  }

  @GetMapping("/v1/checklist-templates/{id}")
  @PreAuthorize(VIEW)
  public TemplateResponse template(@PathVariable UUID id) {
    return TemplateResponse.from(checklists.template(id));
  }

  @PostMapping("/v1/checklist-templates")
  @PreAuthorize("@perm.has('checklist:manage')")
  public ResponseEntity<TemplateResponse> createTemplate(@Valid @RequestBody TemplateRequest r) {
    TemplateResponse saved = TemplateResponse.from(checklists.createTemplate(r.toInput()));
    return ResponseEntity.created(URI.create("/v1/checklist-templates/" + saved.id())).body(saved);
  }

  @PutMapping("/v1/checklist-templates/{id}")
  @PreAuthorize("@perm.has('checklist:manage')")
  public TemplateResponse updateTemplate(@PathVariable UUID id, @Valid @RequestBody TemplateRequest r) {
    return TemplateResponse.from(checklists.updateTemplate(id, r.toInput()));
  }

  @PostMapping("/v1/checklist-templates/{id}/deactivate")
  @PreAuthorize("@perm.has('checklist:manage')")
  public TemplateResponse deactivate(@PathVariable UUID id) {
    return TemplateResponse.from(checklists.setActive(id, false));
  }

  @PostMapping("/v1/checklist-templates/{id}/activate")
  @PreAuthorize("@perm.has('checklist:manage')")
  public TemplateResponse activate(@PathVariable UUID id) {
    return TemplateResponse.from(checklists.setActive(id, true));
  }

  // --- runs -------------------------------------------------------------------------------

  @GetMapping("/v1/checklist-runs")
  @PreAuthorize(VIEW)
  public List<RunSummary> runs(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return checklists.runsOn(date).stream().map(RunSummary::from).toList();
  }

  /** Starts a run, or returns the one already started for this template, date and shift. */
  @PostMapping("/v1/checklist-runs")
  @PreAuthorize("@perm.has('checklist:execute')")
  public RunResponse start(@Valid @RequestBody StartRunRequest r) {
    return RunResponse.from(checklists.start(r.templateId(), r.date(), r.shift()));
  }

  @GetMapping("/v1/checklist-runs/{id}")
  @PreAuthorize(VIEW)
  public RunResponse run(@PathVariable UUID id) {
    return RunResponse.from(checklists.run(id));
  }

  @PostMapping("/v1/checklist-runs/{id}/submit")
  @PreAuthorize("@perm.has('checklist:execute')")
  public RunResponse submit(@PathVariable UUID id, @Valid @RequestBody SubmitRequest r) {
    return RunResponse.from(checklists.submit(id, r.answers(), r.remarks()));
  }
}
