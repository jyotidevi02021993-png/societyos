package in.societyos.ticket.attachment.application;

import in.societyos.ticket.attachment.domain.TicketMedia;
import in.societyos.ticket.attachment.infrastructure.TicketMediaRepository;
import in.societyos.ticket.platform.core.error.ProblemException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Photos of complaints and breakdowns, by media-service id (uploaded by the app beforehand). */
@Service
public class TicketAttachments {

  static final int MAX_PHOTOS = 10;

  private final TicketMediaRepository media;

  public TicketAttachments(TicketMediaRepository media) {
    this.media = media;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void attach(String ticketType, UUID ticketId, List<UUID> mediaIds) {
    if (mediaIds == null || mediaIds.isEmpty()) {
      return;
    }
    var unique = new LinkedHashSet<>(mediaIds);
    unique.remove(null);
    if (unique.size() > MAX_PHOTOS) {
      throw ProblemException.badRequest("TOO_MANY_PHOTOS", "At most " + MAX_PHOTOS + " photos");
    }
    unique.forEach(id -> media.save(new TicketMedia(ticketType, ticketId, id)));
  }

  @Transactional(readOnly = true)
  public List<UUID> of(String ticketType, UUID ticketId) {
    return media.findByTicketTypeAndTicketIdOrderByCreatedAtAsc(ticketType, ticketId).stream()
        .map(TicketMedia::getMediaId).toList();
  }
}
