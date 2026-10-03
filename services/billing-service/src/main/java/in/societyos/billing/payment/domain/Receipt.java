package in.societyos.billing.payment.domain;

import in.societyos.billing.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Receipt RCPT-… for a succeeded payment. Locked on issue (DB trigger). */
@Entity
@Table(name = "receipt")
public class Receipt extends TenantEntity {

  @Column(name = "payment_id", nullable = false)
  private UUID paymentId;

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(name = "flat_label", nullable = false)
  private String flatLabel;

  @Column(nullable = false)
  private String number;

  @Column(name = "amount_paise", nullable = false)
  private long amountPaise;

  @Column(nullable = false)
  private String method;

  private String reference;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "pdf_media_id")
  private UUID pdfMediaId;

  @Column(name = "locked_at", nullable = false)
  private Instant lockedAt;

  protected Receipt() {}

  public Receipt(Payment payment, String flatLabel, String number, Instant at) {
    this.paymentId = payment.getId();
    this.flatId = payment.getFlatId();
    this.flatLabel = flatLabel;
    this.number = number;
    this.amountPaise = payment.getAmountPaise();
    this.method = payment.getMethod();
    this.reference = payment.getReference();
    this.issuedAt = at;
    this.lockedAt = at;
  }

  public UUID getPaymentId() { return paymentId; }
  public UUID getFlatId() { return flatId; }
  public String getFlatLabel() { return flatLabel; }
  public String getNumber() { return number; }
  public long getAmountPaise() { return amountPaise; }
  public String getMethod() { return method; }
  public String getReference() { return reference; }
  public Instant getIssuedAt() { return issuedAt; }
  public UUID getPdfMediaId() { return pdfMediaId; }
}
