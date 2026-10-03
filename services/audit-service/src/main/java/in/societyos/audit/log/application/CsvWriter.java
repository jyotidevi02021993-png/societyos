package in.societyos.audit.log.application;

import in.societyos.audit.log.domain.AuditEntry;
import java.util.List;

/** RFC 4180 CSV for audit exports, with formula-injection protection for spreadsheet users. */
public final class CsvWriter {

  public static final List<String> HEADER = List.of("occurredAt", "type", "subjectType", "subjectId", "actorId",
      "actorType", "source", "eventId", "seq", "hash", "payload");

  private CsvWriter() {}

  public static String header() {
    return String.join(",", HEADER) + "\r\n";
  }

  public static String row(AuditEntry e) {
    return String.join(",",
        cell(e.occurredAt()), cell(e.type()), cell(e.subjectType()), cell(e.subjectId()), cell(e.actorId()),
        cell(e.actorType()), cell(e.source()), cell(e.eventId()), cell(e.seq()), cell(e.hash()),
        cell(e.payload() == null ? null : e.payload().toString())) + "\r\n";
  }

  /** Quotes when needed; a leading = + - @ (or tab/CR) is prefixed with ' so Excel never evaluates it. */
  public static String cell(Object value) {
    if (value == null) {
      return "";
    }
    String s = value.toString();
    if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
      s = "'" + s;
    }
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
      s = "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }
}
