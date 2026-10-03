package in.societyos.billing.ledger.infrastructure;

import in.societyos.billing.ledger.domain.LedgerEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

  interface Balance {
    String getAccount();

    long getDebit();

    long getCredit();
  }

  interface FlatBalance {
    UUID getFlatId();

    long getBalance();
  }

  @Query("select coalesce(sum(e.debitPaise - e.creditPaise), 0) from LedgerEntry e"
      + " where e.accountCode = 'MEMBER_RECEIVABLE' and e.flatId = :flatId")
  long receivableOf(UUID flatId);

  @Query("select e.flatId as flatId, sum(e.debitPaise - e.creditPaise) as balance from LedgerEntry e"
      + " where e.accountCode = 'MEMBER_RECEIVABLE' and e.flatId is not null group by e.flatId")
  List<FlatBalance> receivables();

  @Query("select e from LedgerEntry e where e.accountCode = 'MEMBER_RECEIVABLE' and e.flatId = :flatId"
      + " order by e.at asc, e.id asc")
  List<LedgerEntry> statementOf(UUID flatId);

  @Query("select e from LedgerEntry e where e.entryDate between :from and :to order by e.at asc, e.txnId asc, e.id asc")
  List<LedgerEntry> between(LocalDate from, LocalDate to);

  @Query("select e.accountCode as account, coalesce(sum(e.debitPaise), 0) as debit, coalesce(sum(e.creditPaise), 0) as credit"
      + " from LedgerEntry e where e.entryDate <= :to group by e.accountCode")
  List<Balance> accountTotals(LocalDate to);
}
