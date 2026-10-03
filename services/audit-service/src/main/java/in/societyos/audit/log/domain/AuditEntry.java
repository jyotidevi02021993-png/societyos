package in.societyos.audit.log.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One audit row, built from a CloudEvent. Immutable: the table refuses UPDATE and DELETE.
 *
 * @param seq position in the society's hash chain (assigned when appended; 0 before)
 */
public record AuditEntry(
    UUID id,
    UUID societyId,
    long seq,
    UUID eventId,
    Instant occurredAt,
    Instant recordedAt,
    String source,
    String context,
    String type,
    String subject,
    String subjectType,
    UUID subjectId,
    UUID actorId,
    String actorType,
    JsonNode payload,
    boolean payloadTrimmed,
    String prevHash,
    String hash) {

  /** A new entry for an incoming event; time is truncated to ms so cursors are exact. */
  public static AuditEntry fromEvent(UUID id, UUID societyId, UUID eventId, Instant time, String source, String type,
      String subject, UUID actorId, String actorType, JsonNode data, Instant now) {
    PayloadTrimmer.Result trimmed = PayloadTrimmer.trim(data);
    Instant at = (time == null ? now : time).truncatedTo(ChronoUnit.MILLIS);
    String subjectType = subjectType(subject);
    return new AuditEntry(id, societyId, 0, eventId, at, now, source, context(type), type, blankToNull(subject),
        subjectType, subjectId(subject), actorId, blankToNull(actorType), trimmed.payload(), trimmed.trimmed(),
        null, null);
  }

  /** {@code security.entry.approved} → {@code security}. */
  public static String context(String type) {
    if (type == null || type.isBlank()) {
      return "unknown";
    }
    int dot = type.indexOf('.');
    return dot < 0 ? type : type.substring(0, dot);
  }

  /** {@code entry/0192…} → {@code entry}. */
  public static String subjectType(String subject) {
    if (subject == null || subject.isBlank()) {
      return null;
    }
    int slash = subject.indexOf('/');
    return slash <= 0 ? null : subject.substring(0, slash);
  }

  /** {@code entry/0192…} → the UUID, or null when the subject has no UUID part. */
  public static UUID subjectId(String subject) {
    if (subject == null) {
      return null;
    }
    int slash = subject.lastIndexOf('/');
    if (slash < 0 || slash == subject.length() - 1) {
      return null;
    }
    try {
      return UUID.fromString(subject.substring(slash + 1));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
