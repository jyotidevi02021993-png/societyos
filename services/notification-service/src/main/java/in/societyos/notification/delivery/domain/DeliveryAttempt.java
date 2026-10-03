package in.societyos.notification.delivery.domain;

import in.societyos.notification.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Delivery log: one row per attempt, with the provider's reference or the error. */
@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttempt extends TenantEntity {

  @Column(name = "delivery_id", nullable = false) private UUID deliveryId;
  @Column(name = "attempt_no", nullable = false) private int attemptNo;
  @Column(nullable = false) private Instant at;
  @Column(nullable = false) private String outcome;
  private String provider;
  @Column(name = "provider_ref") private String providerRef;
  private String error;

  protected DeliveryAttempt() {}

  public DeliveryAttempt(UUID deliveryId, int attemptNo, Instant at, String outcome, String provider,
      String providerRef, String error) {
    this.deliveryId = deliveryId;
    this.attemptNo = attemptNo;
    this.at = at;
    this.outcome = outcome;
    this.provider = provider;
    this.providerRef = providerRef;
    this.error = error == null || error.length() <= 500 ? error : error.substring(0, 500);
  }

  public UUID getDeliveryId() { return deliveryId; }
  public int getAttemptNo() { return attemptNo; }
  public Instant getAt() { return at; }
  public String getOutcome() { return outcome; }
  public String getProvider() { return provider; }
  public String getProviderRef() { return providerRef; }
  public String getError() { return error; }
}
