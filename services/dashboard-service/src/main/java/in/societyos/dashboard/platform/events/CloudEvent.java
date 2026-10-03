package in.societyos.dashboard.platform.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The CloudEvents 1.0 envelope as a listener receives it.
 *
 * @param societyId tenant of the event; null for platform-level events (e.g. user registered)
 * @param actorId user who caused the event; null for system actions
 */
public record CloudEvent<T>(
    UUID id,
    String source,
    String type,
    Instant time,
    String subject,
    UUID societyId,
    UUID actorId,
    String actorType,
    T data) {}
