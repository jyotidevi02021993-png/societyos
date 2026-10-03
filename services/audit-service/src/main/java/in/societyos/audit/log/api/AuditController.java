package in.societyos.audit.log.api;

import in.societyos.audit.log.application.AuditQuery;
import in.societyos.audit.log.application.AuditQuery.AuditView;
import in.societyos.audit.log.application.AuditQuery.Export;
import in.societyos.audit.log.domain.AuditFilter;
import in.societyos.audit.platform.core.error.ProblemException;
import in.societyos.audit.platform.web.CursorPage;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only audit trail of the active society. There is no write API: rows come only from events. */
@RestController
@RequestMapping("/v1/audit")
@PreAuthorize("@perm.has('audit:view')")
public class AuditController {

  private final AuditQuery query;

  public AuditController(AuditQuery query) {
    this.query = query;
  }

  @GetMapping
  public CursorPage<AuditView> search(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String context,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) UUID subjectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return query.search(filter(type, context, actorId, subjectType, subjectId, from, to), cursor, limit);
  }

  /** CSV for auditors (same filters; newest first; capped by sos.audit.export-max-rows). */
  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String context,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) UUID subjectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    Export csv = query.exportCsv(filter(type, context, actorId, subjectType, subjectId, from, to));
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + csv.fileName() + "\"")
        .header("X-Row-Count", String.valueOf(csv.rows()))
        .body(csv.csv());
  }

  /** Recomputes the hash chain for a sequence range (default: all). */
  @GetMapping("/verify")
  public AuditQuery.ChainCheck verify(@RequestParam(required = false) Long fromSeq, @RequestParam(required = false) Long toSeq) {
    return query.verify(fromSeq, toSeq);
  }

  private static AuditFilter filter(String type, String context, UUID actorId, String subjectType, UUID subjectId,
      Instant from, Instant to) {
    try {
      return new AuditFilter(type, context, actorId, subjectType, subjectId, from, to);
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_FILTER", e.getMessage());
    }
  }
}
