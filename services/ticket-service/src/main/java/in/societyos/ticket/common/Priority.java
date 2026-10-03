package in.societyos.ticket.common;

import in.societyos.ticket.platform.core.error.ProblemException;
import java.util.List;
import java.util.Locale;

/** Ticket priority P1 (critical) .. P4 (low). */
public final class Priority {

  public static final List<String> ALL = List.of("P1", "P2", "P3", "P4");

  private Priority() {}

  /** Normalises and validates; {@code fallback} when blank. */
  public static String of(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    String p = value.trim().toUpperCase(Locale.ROOT);
    if (!ALL.contains(p)) {
      throw ProblemException.badRequest("INVALID_PRIORITY", "priority must be one of " + ALL);
    }
    return p;
  }
}
