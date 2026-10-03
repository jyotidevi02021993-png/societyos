package in.societyos.audit.log.application;

import in.societyos.audit.log.domain.AuditEntry;
import in.societyos.audit.log.infrastructure.AuditLogStore;
import in.societyos.audit.platform.core.UuidV7;
import in.societyos.audit.platform.events.CloudEvent;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Appends one consumed event to the trail. Runs inside the listener's transaction (inbox row +
 * append commit together), with the event's society bound, so RLS checks the write.
 */
@Service
public class AuditRecorder {

  private final AuditLogStore store;
  private final Clock clock;

  public AuditRecorder(AuditLogStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional
  public void record(CloudEvent<JsonNode> e) {
    AuditEntry entry = AuditEntry.fromEvent(UuidV7.next(), e.societyId(), e.id(), e.time(), e.source(), e.type(),
        e.subject(), e.actorId(), e.actorType(), e.data(), clock.instant());
    if (e.societyId() == null) {
      store.appendPlatform(entry);
      return;
    }
    store.append(entry, store.lockChain(e.societyId()));
  }
}
