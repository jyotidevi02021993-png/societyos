package in.societyos.community.notice.api;

import in.societyos.community.notice.application.NoticeService;
import in.societyos.community.notice.application.NoticeService.NoticeView;
import in.societyos.community.notice.domain.Audience;
import in.societyos.community.notice.domain.Notice;
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
@RequestMapping("/v1/notices")
class NoticeController {

  private final NoticeService notices;

  NoticeController(NoticeService notices) {
    this.notices = notices;
  }

  record AudienceDto(Boolean all, List<UUID> towerIds, List<String> roles) {
    Audience toAudience() {
      return new Audience(all == null ? (isEmpty(towerIds) && isEmpty(roles)) : all, towerIds, roles);
    }

    private static boolean isEmpty(List<?> l) {
      return l == null || l.isEmpty();
    }
  }

  record NoticeRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 20_000) String body,
      AudienceDto audience, List<UUID> attachmentMediaIds, Boolean pinned, Instant publishAt, Instant expiresAt) {}

  record NoticeResponse(UUID id, String title, String body, Audience audience, List<UUID> attachmentMediaIds,
      boolean pinned, Instant publishAt, Instant expiresAt, String status, Instant publishedAt, boolean read,
      Long readCount) {
    static NoticeResponse from(NoticeView v) {
      Notice n = v.notice();
      return new NoticeResponse(n.getId(), n.getTitle(), n.getBody(), v.audience(), v.attachments(), n.isPinned(),
          n.getPublishAt(), n.getExpiresAt(), n.getStatus(), n.getPublishedAt(), v.read(), v.readCount());
    }
  }

  record ReadReceipt(UUID userId, Instant readAt) {}

  @GetMapping
  @PreAuthorize("@perm.hasAny('notice:view', 'notice:publish')")
  List<NoticeResponse> feed(@RequestParam(required = false) Integer limit) {
    return notices.feed(limit).stream().map(NoticeResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('notice:publish')")
  ResponseEntity<NoticeResponse> create(@Valid @RequestBody NoticeRequest r) {
    NoticeView v = notices.create(new Notice.Details(r.title(), r.body(), Boolean.TRUE.equals(r.pinned()), r.publishAt(), r.expiresAt()),
        r.audience() == null ? null : r.audience().toAudience(), r.attachmentMediaIds());
    return ResponseEntity.created(URI.create("/v1/notices/" + v.notice().getId())).body(NoticeResponse.from(v));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('notice:view', 'notice:publish')")
  NoticeResponse get(@PathVariable UUID id) {
    return NoticeResponse.from(notices.get(id));
  }

  /** Read receipt for the caller. */
  @PostMapping("/{id}/read")
  @PreAuthorize("@perm.hasAny('notice:view', 'notice:publish')")
  NoticeResponse read(@PathVariable UUID id) {
    return NoticeResponse.from(notices.markRead(id));
  }

  @GetMapping("/{id}/reads")
  @PreAuthorize("@perm.has('notice:publish')")
  List<ReadReceipt> reads(@PathVariable UUID id) {
    return notices.readReceipts(id).stream().map(r -> new ReadReceipt(r.getUserId(), r.getReadAt())).toList();
  }

  @PostMapping("/{id}/withdraw")
  @PreAuthorize("@perm.has('notice:publish')")
  NoticeResponse withdraw(@PathVariable UUID id) {
    return NoticeResponse.from(notices.withdraw(id));
  }
}
