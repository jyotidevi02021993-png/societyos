package in.societyos.community.notice.domain;

import in.societyos.community.common.CommunityDomainEvent;
import java.time.Instant;
import java.util.UUID;

/** {@code community.notice.published} (catalogue: noticeId, title, audience, pinned, publishAt). */
public record NoticePublished(UUID noticeId, String title, Audience audience, boolean pinned, Instant publishAt)
    implements CommunityDomainEvent {
  @Override public String type() { return "community.notice.published"; }
  @Override public UUID aggregateId() { return noticeId; }
}
