package in.societyos.community.notice.application;

import in.societyos.community.directory.application.DirectoryService;
import in.societyos.community.notice.domain.Audience;
import in.societyos.community.notice.domain.Notice;
import in.societyos.community.notice.domain.NoticePublished;
import in.societyos.community.notice.domain.NoticeRead;
import in.societyos.community.notice.infrastructure.NoticeReadRepository;
import in.societyos.community.notice.infrastructure.NoticeRepository;
import in.societyos.community.notification.application.CommunityNotifier;
import in.societyos.community.notification.domain.NotificationRequested;
import in.societyos.community.platform.core.UuidV7;
import in.societyos.community.platform.core.error.ProblemException;
import in.societyos.community.platform.core.tenant.Tenant;
import in.societyos.community.platform.core.tenant.TenantContext;
import in.societyos.community.platform.events.DomainEvents;
import in.societyos.community.platform.security.PermissionEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Notices: publish (now or scheduled), targeted feed, read receipts, withdraw. */
@Service
public class NoticeService {

  public static final String PUBLISH = "notice:publish";
  static final int MAX_FEED = 200;

  /** A notice as the caller sees it; {@code readCount} only for publishers. */
  public record NoticeView(Notice notice, Audience audience, List<UUID> attachments, boolean read, Long readCount) {}

  private final NoticeRepository notices;
  private final NoticeReadRepository reads;
  private final DirectoryService directory;
  private final PermissionEvaluator perm;
  private final CommunityNotifier notifier;
  private final DomainEvents events;
  private final JsonMapper json;
  private final Clock clock;

  public NoticeService(NoticeRepository notices, NoticeReadRepository reads, DirectoryService directory,
      PermissionEvaluator perm, CommunityNotifier notifier, DomainEvents events, JsonMapper json, Clock clock) {
    this.notices = notices;
    this.reads = reads;
    this.directory = directory;
    this.perm = perm;
    this.notifier = notifier;
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public NoticeView create(Notice.Details details, Audience audience, List<UUID> attachments) {
    Instant now = clock.instant();
    Audience a = (audience == null ? Audience.everyone() : audience).validated();
    Instant publishAt = details.publishAt() == null ? now : details.publishAt();
    if (details.expiresAt() != null && !details.expiresAt().isAfter(publishAt)) {
      throw ProblemException.badRequest("INVALID_EXPIRY", "expiresAt must be after publishAt");
    }
    List<UUID> media = attachments == null ? List.of() : List.copyOf(new LinkedHashSet<>(attachments));
    if (media.size() > 10) {
      throw ProblemException.badRequest("TOO_MANY_ATTACHMENTS", "At most 10 attachments");
    }
    Notice n = new Notice(new Notice.Details(details.title().trim(), details.body().trim(), details.pinned(),
        publishAt, details.expiresAt()), json.writeValueAsString(a), json.writeValueAsString(media));
    notices.save(n);
    if (n.publishIfDue(now)) {
      announce(n, a);
    }
    return new NoticeView(n, a, media, false, 0L);
  }

  /** Scheduled notices whose time has come (db-scheduler job, per society). */
  @Transactional
  public int publishDue() {
    Instant now = clock.instant();
    int count = 0;
    for (Notice n : notices.findBySocietyIdAndStatusAndPublishAtLessThanEqual(
        TenantContext.activeSocietyId(), "SCHEDULED", now)) {
      if (n.publishIfDue(now)) {
        announce(n, audience(n));
        count++;
      }
    }
    return count;
  }

  @Transactional(readOnly = true)
  public List<NoticeView> feed(Integer limit) {
    int size = limit == null ? 50 : Math.max(1, Math.min(limit, MAX_FEED));
    boolean publisher = perm.has(PUBLISH);
    List<String> statuses = publisher ? List.of("SCHEDULED", "PUBLISHED", "WITHDRAWN") : List.of("PUBLISHED");
    Reader reader = reader();
    List<Notice> visible = notices.feed(TenantContext.activeSocietyId(), statuses, PageRequest.of(0, MAX_FEED)).stream()
        .filter(n -> publisher || canRead(n, reader))
        .limit(size)
        .toList();
    Set<UUID> readIds = reader.userId() == null || visible.isEmpty() ? Set.of()
        : new HashSet<>(reads.readBy(reader.userId(), visible.stream().map(Notice::getId).toList()));
    return visible.stream()
        .map(n -> view(n, readIds.contains(n.getId()), publisher ? reads.countByNoticeId(n.getId()) : null))
        .toList();
  }

  @Transactional(readOnly = true)
  public NoticeView get(UUID id) {
    Notice n = readable(id);
    UUID me = TenantContext.userId().orElse(null);
    boolean read = me != null && !reads.readBy(me, List.of(id)).isEmpty();
    return view(n, read, perm.has(PUBLISH) ? reads.countByNoticeId(id) : null);
  }

  /** Records that the caller has read the notice (idempotent). */
  @Transactional
  public NoticeView markRead(UUID id) {
    Notice n = readable(id);
    if (!n.isVisibleAt(clock.instant())) {
      throw ProblemException.unprocessable("NOTICE_NOT_PUBLISHED", "Only a published notice can be read");
    }
    UUID me = TenantContext.userId()
        .orElseThrow(() -> ProblemException.forbidden("USER_REQUIRED", "A signed-in user is required"));
    reads.markRead(UuidV7.next(), TenantContext.activeSocietyId(), id, me);
    return view(n, true, perm.has(PUBLISH) ? reads.countByNoticeId(id) : null);
  }

  @Transactional(readOnly = true)
  public List<NoticeRead> readReceipts(UUID id) {
    load(id);
    return reads.findByNoticeIdOrderByReadAtAsc(id);
  }

  @Transactional
  public NoticeView withdraw(UUID id) {
    Notice n = load(id);
    n.withdraw();
    return view(n, false, reads.countByNoticeId(id));
  }

  // --- helpers -----------------------------------------------------------------------------

  private void announce(Notice n, Audience a) {
    events.publish(new NoticePublished(n.getId(), n.getTitle(), a, n.isPinned(), n.getPublishAt()));
    Set<UUID> users = new LinkedHashSet<>();
    if (a.all()) {
      users.addAll(directory.allResidents());
    } else if (!a.towerIds().isEmpty()) {
      users.addAll(directory.residentsOfTowers(a.towerIds()));
    }
    notifier.notify(users, a.all() ? List.of() : a.roles(), NotificationRequested.NOTICE,
        "community.notice.published", Map.of("title", n.getTitle(), "noticeId", n.getId().toString()),
        "community.notice:" + n.getId());
  }

  private record Reader(UUID userId, Set<UUID> towers, Set<String> roles) {}

  private Reader reader() {
    Tenant t = TenantContext.current();
    return new Reader(t.userId(), directory.towersOf(t.userId()), t.roles());
  }

  private boolean canRead(Notice n, Reader r) {
    return n.isVisibleAt(clock.instant()) && audience(n).matches(r.towers(), r.roles());
  }

  private Notice load(UUID id) {
    return notices.findByIdAndSocietyId(id, TenantContext.activeSocietyId())
        .orElseThrow(() -> ProblemException.notFound("notice", id));
  }

  /** Publishers see every notice; everyone else only published notices addressed to them. */
  private Notice readable(UUID id) {
    Notice n = load(id);
    if (!perm.has(PUBLISH) && !canRead(n, reader())) {
      throw ProblemException.notFound("notice", id);
    }
    return n;
  }

  private Audience audience(Notice n) {
    return json.readValue(n.getAudienceJson(), Audience.class);
  }

  private NoticeView view(Notice n, boolean read, Long readCount) {
    List<UUID> media = json.readValue(n.getAttachmentsJson(), new TypeReference<List<UUID>>() {});
    return new NoticeView(n, audience(n), media, read, readCount);
  }
}
