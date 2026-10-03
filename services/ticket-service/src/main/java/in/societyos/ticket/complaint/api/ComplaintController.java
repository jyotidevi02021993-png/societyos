package in.societyos.ticket.complaint.api;

import in.societyos.ticket.complaint.application.ComplaintService;
import in.societyos.ticket.complaint.application.ComplaintService.ComplaintDetail;
import in.societyos.ticket.complaint.application.ComplaintService.RaiseComplaint;
import in.societyos.ticket.complaint.domain.Complaint;
import in.societyos.ticket.history.domain.TicketHistoryEntry;
import in.societyos.ticket.jobcard.domain.JobCard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Complaints. Residents ({@code complaint:create}) raise and follow their own; viewers
 * ({@code complaint:view}) see all; managers ({@code complaint:manage}) triage and resolve.
 */
@RestController
@RequestMapping("/v1/complaints")
class ComplaintController {

  private static final String ANY_COMPLAINT = "@perm.hasAny('complaint:create', 'complaint:view', 'complaint:manage')";

  private final ComplaintService complaints;

  ComplaintController(ComplaintService complaints) {
    this.complaints = complaints;
  }

  record ComplaintRequest(UUID flatId, UUID locationId, UUID assetId, UUID categoryId, String category,
      @NotBlank @Size(max = 4000) String text, String priority, @Size(max = 10) List<UUID> mediaIds) {}

  record JobCardRequest(UUID assigneeUserId, UUID vendorId, String priority) {}

  record NoteRequest(String note) {}

  record ReasonRequest(@NotBlank String reason) {}

  record FeedbackRequest(@NotNull Boolean accepted, @Min(1) @Max(5) Integer rating, @Size(max = 2000) String comment) {}

  record ReopenRequest(String reason) {}

  record ComplaintResponse(UUID id, String number, UUID flatId, UUID locationId, UUID assetId, UUID raisedBy,
      UUID categoryId, String category, String priority, String text, String status, UUID jobCardId,
      Instant slaDueAt, boolean slaBreached, int escalationLevel, String escalatedToRole, Instant resolvedAt,
      Instant closedAt, Integer rating, String feedback, int reopenedCount, Instant createdAt) {
    static ComplaintResponse from(Complaint c) {
      return new ComplaintResponse(c.getId(), c.getNumber(), c.getFlatId(), c.getLocationId(), c.getAssetId(),
          c.getRaisedBy(), c.getCategoryId(), c.getCategoryName(), c.getPriority(), c.getDescription(),
          c.getStatus().name(), c.getJobCardId(), c.getSlaDueAt(), c.isSlaBreached(), c.getEscalationLevel(),
          c.getEscalatedToRole(), c.getResolvedAt(), c.getClosedAt(), c.getRating(), c.getFeedback(),
          c.getReopenedCount(), c.getCreatedAt());
    }
  }

  record JobCardSummary(UUID id, String number, String status, UUID assigneeUserId, UUID vendorId) {
    static JobCardSummary from(JobCard j) {
      return j == null ? null
          : new JobCardSummary(j.getId(), j.getNumber(), j.getStatus().name(), j.getAssigneeUserId(), j.getVendorId());
    }
  }

  record HistoryResponse(Instant at, UUID actorId, String fromStatus, String toStatus, String note) {
    static HistoryResponse from(TicketHistoryEntry h) {
      return new HistoryResponse(h.getAt(), h.getActorId(), h.getFromStatus(), h.getToStatus(), h.getNote());
    }
  }

  record ComplaintDetailResponse(ComplaintResponse complaint, String flatLabel, List<UUID> mediaIds,
      JobCardSummary jobCard, List<HistoryResponse> history) {
    static ComplaintDetailResponse from(ComplaintDetail d) {
      return new ComplaintDetailResponse(ComplaintResponse.from(d.complaint()), d.flatLabel(), d.mediaIds(),
          JobCardSummary.from(d.jobCard()), d.history().stream().map(HistoryResponse::from).toList());
    }
  }

  @PostMapping
  @PreAuthorize("@perm.hasAny('complaint:create', 'complaint:manage')")
  ResponseEntity<ComplaintResponse> raise(@Valid @RequestBody ComplaintRequest r) {
    Complaint c = complaints.raise(new RaiseComplaint(r.flatId(), r.locationId(), r.assetId(), r.categoryId(),
        r.category(), r.text(), r.priority(), r.mediaIds()));
    return ResponseEntity.created(URI.create("/v1/complaints/" + c.getId())).body(ComplaintResponse.from(c));
  }

  @GetMapping
  @PreAuthorize(ANY_COMPLAINT)
  List<ComplaintResponse> list(@RequestParam(required = false) String status) {
    return complaints.list(status).stream().map(ComplaintResponse::from).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize(ANY_COMPLAINT)
  ComplaintDetailResponse get(@PathVariable UUID id) {
    return ComplaintDetailResponse.from(complaints.detail(id));
  }

  @PostMapping("/{id}/jobcard")
  @PreAuthorize("@perm.has('complaint:manage')")
  @ResponseStatus(HttpStatus.CREATED)
  JobCardSummary raiseJobCard(@PathVariable UUID id, @RequestBody(required = false) JobCardRequest r) {
    JobCardRequest req = r == null ? new JobCardRequest(null, null, null) : r;
    return JobCardSummary.from(complaints.raiseJobCard(id, req.assigneeUserId(), req.vendorId(), req.priority()));
  }

  @PostMapping("/{id}/resolve")
  @PreAuthorize("@perm.has('complaint:manage')")
  ComplaintResponse resolve(@PathVariable UUID id, @RequestBody(required = false) NoteRequest r) {
    return ComplaintResponse.from(complaints.resolve(id, r == null ? null : r.note()));
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize("@perm.has('complaint:manage')")
  ComplaintResponse reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
    return ComplaintResponse.from(complaints.reject(id, r.reason()));
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("@perm.hasAny('complaint:create', 'complaint:manage')")
  ComplaintResponse cancel(@PathVariable UUID id) {
    return ComplaintResponse.from(complaints.cancel(id));
  }

  @PostMapping("/{id}/feedback")
  @PreAuthorize("@perm.has('complaint:create')")
  ComplaintResponse feedback(@PathVariable UUID id, @Valid @RequestBody FeedbackRequest r) {
    return ComplaintResponse.from(complaints.feedback(id, r.accepted(), r.rating(), r.comment()));
  }

  @PostMapping("/{id}/reopen")
  @PreAuthorize("@perm.hasAny('complaint:create', 'complaint:manage')")
  ComplaintResponse reopen(@PathVariable UUID id, @RequestBody(required = false) ReopenRequest r) {
    return ComplaintResponse.from(complaints.reopen(id, r == null ? null : r.reason()));
  }
}
