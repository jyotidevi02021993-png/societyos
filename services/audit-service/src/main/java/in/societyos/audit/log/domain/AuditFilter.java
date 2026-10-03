package in.societyos.audit.log.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Search filters; every field is optional. {@code type} ending in {@code *} is a prefix match. */
public record AuditFilter(
    String type,
    String context,
    UUID actorId,
    String subjectType,
    UUID subjectId,
    Instant from,
    Instant to) {

  public AuditFilter {
    if (from != null && to != null && !from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
  }

  /** Compact description stored with an export, e.g. {@code type=security.*;from=2026-09-01T00:00:00Z}. */
  public String describe() {
    List<String> parts = new ArrayList<>();
    add(parts, "type", type);
    add(parts, "context", context);
    add(parts, "actorId", actorId);
    add(parts, "subjectType", subjectType);
    add(parts, "subjectId", subjectId);
    add(parts, "from", from);
    add(parts, "to", to);
    return parts.isEmpty() ? "all" : String.join(";", parts);
  }

  private static void add(List<String> parts, String name, Object value) {
    if (value != null && !value.toString().isBlank()) {
      parts.add(name + "=" + value);
    }
  }
}
