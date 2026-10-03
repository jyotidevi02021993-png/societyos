package in.societyos.billing.ledger.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One side of a double-entry transaction. Append-only (a DB trigger rejects updates and deletes). */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry extends TenantEntity {

  @Column(name = "txn_id", nullable = false, updatable = false)
  private UUID txnId;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  @Column(name = "account_code", nullable = false, updatable = false)
  private String accountCode;

  @Column(name = "flat_id", updatable = false)
  private UUID flatId;

  @Column(name = "debit_paise", nullable = false, updatable = false)
  private long debitPaise;

  @Column(name = "credit_paise", nullable = false, updatable = false)
  private long creditPaise;

  @Column(name = "ref_type", nullable = false, updatable = false)
  private String refType;

  @Column(name = "ref_id", nullable = false, updatable = false)
  private UUID refId;

  @Column(nullable = false, updatable = false)
  private String narration;

  @Column(name = "entry_date", nullable = false, updatable = false)
  private LocalDate entryDate;

  @Column(nullable = false, updatable = false)
  private Instant at;

  protected LedgerEntry() {}

  public LedgerEntry(UUID txnId, LedgerAccount account, Journal journal, Journal.Line line, Instant at) {
    this.txnId = txnId;
    this.accountId = account.getId();
    this.accountCode = account.getCode();
    this.flatId = line.flatId();
    this.debitPaise = line.debitPaise();
    this.creditPaise = line.creditPaise();
    this.refType = journal.refType();
    this.refId = journal.refId();
    this.narration = journal.narration();
    this.entryDate = journal.date();
    this.at = at;
  }

  public UUID getTxnId() { return txnId; }
  public UUID getAccountId() { return accountId; }
  public String getAccountCode() { return accountCode; }
  public UUID getFlatId() { return flatId; }
  public long getDebitPaise() { return debitPaise; }
  public long getCreditPaise() { return creditPaise; }
  public String getRefType() { return refType; }
  public UUID getRefId() { return refId; }
  public String getNarration() { return narration; }
  public LocalDate getEntryDate() { return entryDate; }
  public Instant getAt() { return at; }
}
