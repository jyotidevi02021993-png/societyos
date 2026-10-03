package in.societyos.billing.ledger.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_account")
public class LedgerAccount extends TenantEntity {

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String kind;

  protected LedgerAccount() {}

  public LedgerAccount(Journal.Account account) {
    this.code = account.name();
    this.name = account.title();
    this.kind = account.kind();
  }

  public String getCode() { return code; }
  public String getName() { return name; }
  public String getKind() { return kind; }
}
