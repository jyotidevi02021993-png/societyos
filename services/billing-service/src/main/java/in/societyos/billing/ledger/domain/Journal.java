package in.societyos.billing.ledger.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One balanced double-entry transaction before it is posted. {@link #lines()} refuses an
 * unbalanced journal; the database checks the same rule again at commit (deferred trigger).
 */
public final class Journal {

  /** The society's chart of accounts (created on first use). */
  public enum Account {
    BANK("Bank", "ASSET"),
    CASH("Cash in hand", "ASSET"),
    MEMBER_RECEIVABLE("Member receivables", "ASSET"),
    MAINTENANCE_INCOME("Maintenance and charges income", "INCOME"),
    OTHER_INCOME("Other income (bookings, one-off charges)", "INCOME"),
    LATE_FEE_INCOME("Late fee income", "INCOME"),
    GST_PAYABLE("GST payable", "LIABILITY"),
    EXPENSES("Expenses", "EXPENSE");

    private final String title;
    private final String kind;

    Account(String title, String kind) {
      this.title = title;
      this.kind = kind;
    }

    public String title() { return title; }
    public String kind() { return kind; }
  }

  public record Line(Account account, UUID flatId, long debitPaise, long creditPaise) {}

  private final String refType;
  private final UUID refId;
  private final String narration;
  private final LocalDate date;
  private final List<Line> lines = new ArrayList<>();

  public Journal(String refType, UUID refId, String narration, LocalDate date) {
    this.refType = refType;
    this.refId = refId;
    this.narration = narration;
    this.date = date;
  }

  public Journal debit(Account account, UUID flatId, long paise) {
    if (paise < 0) {
      throw new IllegalArgumentException("negative debit");
    }
    if (paise > 0) {
      lines.add(new Line(account, flatId, paise, 0));
    }
    return this;
  }

  public Journal credit(Account account, UUID flatId, long paise) {
    if (paise < 0) {
      throw new IllegalArgumentException("negative credit");
    }
    if (paise > 0) {
      lines.add(new Line(account, flatId, 0, paise));
    }
    return this;
  }

  public Journal debit(Account account, long paise) {
    return debit(account, null, paise);
  }

  public Journal credit(Account account, long paise) {
    return credit(account, null, paise);
  }

  public List<Line> lines() {
    long debit = 0;
    long credit = 0;
    for (Line l : lines) {
      debit = Math.addExact(debit, l.debitPaise());
      credit = Math.addExact(credit, l.creditPaise());
    }
    if (debit != credit) {
      throw new IllegalStateException("Unbalanced journal " + refType + " " + refId + ": " + debit + " != " + credit);
    }
    return List.copyOf(lines);
  }

  public String refType() { return refType; }
  public UUID refId() { return refId; }
  public String narration() { return narration; }
  public LocalDate date() { return date; }
}
