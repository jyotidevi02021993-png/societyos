package in.societyos.billing.ledger.api;

import in.societyos.billing.ledger.application.LedgerService;
import in.societyos.billing.ledger.domain.LedgerEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LedgerController {

  private final LedgerService ledger;

  LedgerController(LedgerService ledger) {
    this.ledger = ledger;
  }

  record EntryResponse(UUID id, UUID txnId, LocalDate date, Instant at, String account, UUID flatId,
      long debitPaise, long creditPaise, String refType, UUID refId, String narration) {
    static EntryResponse from(LedgerEntry e) {
      return new EntryResponse(e.getId(), e.getTxnId(), e.getEntryDate(), e.getAt(), e.getAccountCode(), e.getFlatId(),
          e.getDebitPaise(), e.getCreditPaise(), e.getRefType(), e.getRefId(), e.getNarration());
    }
  }

  /** Trial balance: debit, credit and balance per account up to {@code to} (default today). */
  @GetMapping("/v1/ledger/accounts")
  @PreAuthorize("@perm.has('ledger:view')")
  List<LedgerService.AccountBalance> accounts(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ledger.trialBalance(to == null ? LocalDate.now().plusDays(1) : to);
  }

  @GetMapping("/v1/ledger/entries")
  @PreAuthorize("@perm.has('ledger:view')")
  List<EntryResponse> entries(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ledger.entries(from, to).stream().map(EntryResponse::from).toList();
  }

  /** Journal export as CSV (Tally import friendly); amounts in rupees with two decimals. */
  @GetMapping("/v1/ledger/export.csv")
  @PreAuthorize("@perm.has('ledger:view')")
  ResponseEntity<String> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ledger-" + from + "-" + to + ".csv\"")
        .body(ledger.exportCsv(from, to));
  }

  /** A flat's receivable statement with running balance. */
  @GetMapping("/v1/flats/{flatId}/statement")
  @PreAuthorize("@perm.hasAny('bill:view', 'bill:view-own')")
  List<LedgerService.StatementLine> statement(@PathVariable UUID flatId) {
    return ledger.statementFor(flatId);
  }
}
