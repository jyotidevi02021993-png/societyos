package in.societyos.billing.bill.api;

import in.societyos.billing.bill.application.BillRunService;
import in.societyos.billing.bill.domain.BillRun;
import in.societyos.billing.platform.core.error.ProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/v1/bill-runs")
class BillRunController {

  private final BillRunService runs;
  private final JsonMapper json;

  BillRunController(BillRunService runs, JsonMapper json) {
    this.runs = runs;
    this.json = json;
  }

  /** {@code period} is YYYY-MM; {@code dueDate} overrides the society due day for this run. */
  record RunRequest(@NotBlank String period, LocalDate dueDate) {}

  record RunResponse(UUID id, String period, String status, LocalDate billDate, LocalDate dueDate, int billCount,
      long amountPaise, long gstPaise, long totalPaise, JsonNode warnings, Instant publishedAt,
      List<BillController.BillSummary> bills) {}

  private RunResponse view(BillRun r, List<BillController.BillSummary> bills) {
    return new RunResponse(r.getId(), r.getPeriod(), r.getStatus(), r.getBillDate(), r.getDueDate(), r.getBillCount(),
        r.getAmountPaise(), r.getGstPaise(), r.getTotalPaise(), json.readTree(r.getWarnings()), r.getPublishedAt(),
        bills);
  }

  private RunResponse view(BillRunService.RunView v) {
    return view(v.run(), v.bills().stream().map(BillController.BillSummary::from).toList());
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('bill:view', 'bill:generate')")
  List<RunResponse> list() {
    return runs.list().stream().map(r -> view(r, null)).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('bill:view', 'bill:generate')")
  RunResponse get(@PathVariable UUID id) {
    return view(runs.get(id));
  }

  /** Computes the preview (draft bills). Re-posting the same month recomputes the preview. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@perm.has('bill:generate')")
  RunResponse preview(@Valid @RequestBody RunRequest r) {
    YearMonth month;
    try {
      month = YearMonth.parse(r.period().trim());
    } catch (DateTimeParseException e) {
      throw ProblemException.badRequest("INVALID_PERIOD", "period must be YYYY-MM");
    }
    return view(runs.preview(month, r.dueDate()));
  }

  @PostMapping("/{id}/publish")
  @PreAuthorize("@perm.has('bill:generate')")
  RunResponse publish(@PathVariable UUID id) {
    return view(runs.publish(id));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("@perm.has('bill:generate')")
  void discard(@PathVariable UUID id) {
    runs.discard(id);
  }
}
