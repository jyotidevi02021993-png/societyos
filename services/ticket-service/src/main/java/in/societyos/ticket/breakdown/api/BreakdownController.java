package in.societyos.ticket.breakdown.api;

import in.societyos.ticket.breakdown.application.BreakdownService;
import in.societyos.ticket.breakdown.application.BreakdownService.BreakdownDetail;
import in.societyos.ticket.breakdown.application.BreakdownService.ReportBreakdown;
import in.societyos.ticket.breakdown.domain.Breakdown;
import in.societyos.ticket.history.domain.TicketHistoryEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/breakdowns")
class BreakdownController {

  private static final String VIEW = "@perm.hasAny('jobcard:view', 'jobcard:assign', 'complaint:view', 'breakdown:report')";

  private final BreakdownService breakdowns;

  BreakdownController(BreakdownService breakdowns) {
    this.breakdowns = breakdowns;
  }

  record BreakdownRequest(UUID assetId, UUID locationId, @NotBlank @Size(max = 4000) String fault, String priority,
      @Size(max = 10) List<UUID> mediaIds, UUID assigneeUserId, UUID vendorId) {}

  record BreakdownResponse(UUID id, String number, UUID assetId, UUID locationId, UUID reportedBy, String fault,
      String priority, String status, String source, UUID jobCardId, Instant reportedAt,
      Instant expectedResolutionAt, Instant resolvedAt, Integer downtimeMins, boolean slaBreached,
      int escalationLevel, String escalatedToRole) {
    static BreakdownResponse from(Breakdown b) {
      return new BreakdownResponse(b.getId(), b.getNumber(), b.getAssetId(), b.getLocationId(), b.getReportedBy(),
          b.getFault(), b.getPriority(), b.getStatus().name(), b.getSource(), b.getJobCardId(), b.getReportedAt(),
          b.getExpectedResolutionAt(), b.getResolvedAt(), b.getDowntimeMins(), b.isSlaBreached(),
          b.getEscalationLevel(), b.getEscalatedToRole());
    }
  }

  record HistoryResponse(Instant at, UUID actorId, String fromStatus, String toStatus, String note) {
    static HistoryResponse from(TicketHistoryEntry h) {
      return new HistoryResponse(h.getAt(), h.getActorId(), h.getFromStatus(), h.getToStatus(), h.getNote());
    }
  }

  record BreakdownDetailResponse(BreakdownResponse breakdown, String assetName, List<UUID> mediaIds,
      String jobCardNumber, String jobCardStatus, List<HistoryResponse> history) {
    static BreakdownDetailResponse from(BreakdownDetail d) {
      return new BreakdownDetailResponse(BreakdownResponse.from(d.breakdown()), d.assetName(), d.mediaIds(),
          d.jobCard() == null ? null : d.jobCard().getNumber(),
          d.jobCard() == null ? null : d.jobCard().getStatus().name(),
          d.history().stream().map(HistoryResponse::from).toList());
    }
  }

  @PostMapping
  @PreAuthorize("@perm.has('breakdown:report')")
  ResponseEntity<BreakdownResponse> report(@Valid @RequestBody BreakdownRequest r) {
    Breakdown b = breakdowns.report(new ReportBreakdown(r.assetId(), r.locationId(), r.fault(), r.priority(),
        r.mediaIds(), r.assigneeUserId(), r.vendorId()));
    return ResponseEntity.created(URI.create("/v1/breakdowns/" + b.getId())).body(BreakdownResponse.from(b));
  }

  @GetMapping
  @PreAuthorize(VIEW)
  List<BreakdownResponse> list(@RequestParam(required = false) String status) {
    return breakdowns.list(status).stream().map(BreakdownResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize(VIEW)
  BreakdownDetailResponse get(@PathVariable UUID id) {
    return BreakdownDetailResponse.from(breakdowns.detail(id));
  }
}
