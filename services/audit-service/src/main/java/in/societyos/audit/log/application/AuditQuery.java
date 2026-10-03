package in.societyos.audit.log.application;

import in.societyos.audit.log.domain.AuditEntry;
import in.societyos.audit.log.domain.AuditExport;
import in.societyos.audit.log.domain.AuditFilter;
import in.societyos.audit.log.infrastructure.AuditExportRepository;
import in.societyos.audit.log.infrastructure.AuditLogStore;
import in.societyos.audit.platform.core.error.ProblemException;
import in.societyos.audit.platform.core.tenant.TenantContext;
import in.societyos.audit.platform.web.CursorPage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Search, export and chain verification. Always scoped to the active society (the one the
 * {@code audit:view} permission was checked for), with RLS as the second layer.
 */
@Service
public class AuditQuery {

  private final AuditLogStore store;
  private final AuditExportRepository exports;
  private final int exportMaxRows;

  public AuditQuery(AuditLogStore store, AuditExportRepository exports,
      @Value("${sos.audit.export-max-rows:50000}") int exportMaxRows) {
    this.store = store;
    this.exports = exports;
    this.exportMaxRows = exportMaxRows;
  }

  public record AuditView(UUID id, long seq, UUID eventId, Instant occurredAt, String source, String context,
      String type, String subject, String subjectType, UUID subjectId, UUID actorId, String actorType,
      JsonNode payload, boolean payloadTrimmed, String hash) {

    static AuditView of(AuditEntry e) {
      return new AuditView(e.id(), e.seq(), e.eventId(), e.occurredAt(), e.source(), e.context(), e.type(),
          e.subject(), e.subjectType(), e.subjectId(), e.actorId(), e.actorType(), e.payload(), e.payloadTrimmed(),
          e.hash());
    }
  }

  /** {@code intact} when every hash recomputes and every row links to the previous one. */
  public record ChainCheck(boolean intact, long checked, Long firstSeq, Long lastSeq, Long brokenAtSeq,
      long headSeq) {}

  public record Export(String fileName, byte[] csv, int rows) {}

  @Transactional(readOnly = true)
  public CursorPage<AuditView> search(AuditFilter filter, String cursor, Integer limit) {
    int n = CursorPage.clampLimit(limit);
    CursorPage.Cursor c = CursorPage.decode(cursor);
    var rows = store.search(TenantContext.activeSocietyId(), filter, c == null ? null : c.createdAt(),
        c == null ? null : c.id(), n + 1);
    return CursorPage.of(rows, n, AuditView::of, e -> new CursorPage.Cursor(e.occurredAt(), e.id()));
  }

  /** CSV of the matching rows (newest first, capped); the export itself is recorded. */
  @Transactional
  public Export exportCsv(AuditFilter filter) {
    UUID society = TenantContext.activeSocietyId();
    StringBuilder out = new StringBuilder(CsvWriter.header());
    int rows = store.stream(society, filter, exportMaxRows, e -> out.append(CsvWriter.row(e)));
    exports.save(new AuditExport("CSV", filter.describe(), rows));
    String name = "audit-" + society + "-" + Instant.now().toString().replace(":", "").substring(0, 15) + ".csv";
    return new Export(name, out.toString().getBytes(StandardCharsets.UTF_8), rows);
  }

  @Transactional(readOnly = true)
  public ChainCheck verify(Long fromSeq, Long toSeq) {
    long from = fromSeq == null ? 1 : fromSeq;
    long to = toSeq == null ? Long.MAX_VALUE : toSeq;
    if (from < 1 || to < from) {
      throw ProblemException.badRequest("INVALID_RANGE", "Need 1 <= fromSeq <= toSeq");
    }
    var v = store.verify(TenantContext.activeSocietyId(), from, to);
    return new ChainCheck(v.brokenAtSeq() == null, v.checked(), v.firstSeq(), v.lastSeq(), v.brokenAtSeq(),
        v.lastChainSeq());
  }
}
