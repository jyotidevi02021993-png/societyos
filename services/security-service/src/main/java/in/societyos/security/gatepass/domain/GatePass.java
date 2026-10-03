package in.societyos.security.gatepass.domain;

import in.societyos.security.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A resident pre-approval: the guest shows a 6-digit code or a QR token at the gate, which is
 * valid inside [{@code validFrom}, {@code validTo}] for up to {@code maxUses} entries.
 */
@Entity
@Table(name = "gate_pass")
public class GatePass extends TenantEntity {

  public static final Set<String> KINDS = Set.of("GUEST", "CAB", "DELIVERY", "SERVICE");

  /** Guests may arrive a little early; the guard sees this grace as valid. */
  public static final Duration EARLY_GRACE = Duration.ofMinutes(15);

  /** Why a pass cannot be used right now. */
  public enum Rejection {
    CANCELLED,
    EXHAUSTED,
    EXPIRED,
    NOT_YET_VALID
  }

  /** Limits from configuration ({@code sos.gate.max-pass-validity}, {@code sos.gate.max-pass-uses}). */
  public record Limits(Duration maxValidity, int maxUses) {}

  @Column(name = "flat_id", nullable = false)
  private UUID flatId;

  @Column(nullable = false)
  private String kind;

  @Column(name = "guest_name")
  private String guestName;

  @Column(nullable = false)
  private String code;

  @Column(name = "qr_token", nullable = false)
  private String qrToken;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to", nullable = false)
  private Instant validTo;

  @Column(name = "max_uses", nullable = false)
  private int maxUses;

  @Column(name = "used_count", nullable = false)
  private int usedCount;

  @Column(nullable = false)
  private String status = "ACTIVE";

  protected GatePass() {}

  private GatePass(UUID flatId, String kind, String guestName, String code, String qrToken, Instant validFrom,
      Instant validTo, int maxUses) {
    this.flatId = flatId;
    this.kind = kind;
    this.guestName = guestName;
    this.code = code;
    this.qrToken = qrToken;
    this.validFrom = validFrom;
    this.validTo = validTo;
    this.maxUses = maxUses;
  }

  /**
   * Validates and creates a pass. {@code validFrom} defaults to now; the window must end in the
   * future, be no longer than the limit, and {@code maxUses} must be between 1 and the limit.
   */
  public static GatePass issue(UUID flatId, String kind, String guestName, Instant validFrom, Instant validTo,
      Integer maxUses, Instant now, Limits limits, String code, String qrToken) {
    if (flatId == null) {
      throw new IllegalArgumentException("flatId is required");
    }
    if (kind == null || !KINDS.contains(kind)) {
      throw new IllegalArgumentException("kind must be one of " + KINDS);
    }
    Instant from = validFrom == null ? now : validFrom;
    if (from.isBefore(now.minus(Duration.ofMinutes(5)))) {
      throw new IllegalArgumentException("validFrom cannot be in the past");
    }
    if (validTo == null || !validTo.isAfter(from)) {
      throw new IllegalArgumentException("validTo must be after validFrom");
    }
    if (Duration.between(from, validTo).compareTo(limits.maxValidity()) > 0) {
      throw new IllegalArgumentException("A pass can be valid for at most " + limits.maxValidity().toDays() + " days");
    }
    int uses = maxUses == null ? 1 : maxUses;
    if (uses < 1 || uses > limits.maxUses()) {
      throw new IllegalArgumentException("maxUses must be between 1 and " + limits.maxUses());
    }
    if (code == null || !code.matches("\\d{6}")) {
      throw new IllegalArgumentException("code must be 6 digits");
    }
    String name = guestName == null || guestName.isBlank() ? null : guestName.trim();
    return new GatePass(flatId, kind, name, code, qrToken, from, validTo, uses);
  }

  public Optional<Rejection> rejectionAt(Instant now) {
    return switch (status) {
      case "CANCELLED" -> Optional.of(Rejection.CANCELLED);
      case "EXHAUSTED" -> Optional.of(Rejection.EXHAUSTED);
      case "EXPIRED" -> Optional.of(Rejection.EXPIRED);
      default -> {
        if (usedCount >= maxUses) {
          yield Optional.of(Rejection.EXHAUSTED);
        }
        if (!now.isBefore(validTo)) {
          yield Optional.of(Rejection.EXPIRED);
        }
        if (now.isBefore(validFrom.minus(EARLY_GRACE))) {
          yield Optional.of(Rejection.NOT_YET_VALID);
        }
        yield Optional.empty();
      }
    };
  }

  /** One entry on this pass; the last allowed use exhausts it. */
  public void use(Instant now) {
    Optional<Rejection> rejection = rejectionAt(now);
    if (rejection.isPresent()) {
      throw new PassRejectedException(rejection.get());
    }
    usedCount++;
    if (usedCount >= maxUses) {
      status = "EXHAUSTED";
    }
  }

  public void cancel() {
    if (!"ACTIVE".equals(status)) {
      throw new IllegalStateException("Only an active pass can be cancelled");
    }
    status = "CANCELLED";
  }

  /** Called by the expiry job once {@code validTo} has passed. */
  public boolean expireIfPast(Instant now) {
    if ("ACTIVE".equals(status) && !now.isBefore(validTo)) {
      status = "EXPIRED";
      return true;
    }
    return false;
  }

  public int usesLeft() {
    return Math.max(0, maxUses - usedCount);
  }

  public UUID getFlatId() {
    return flatId;
  }

  public String getKind() {
    return kind;
  }

  public String getGuestName() {
    return guestName;
  }

  public String getCode() {
    return code;
  }

  public String getQrToken() {
    return qrToken;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getValidTo() {
    return validTo;
  }

  public int getMaxUses() {
    return maxUses;
  }

  public int getUsedCount() {
    return usedCount;
  }

  public String getStatus() {
    return status;
  }

  /** Thrown by {@link #use} when the pass cannot admit anyone now. */
  public static class PassRejectedException extends RuntimeException {
    private final Rejection rejection;

    public PassRejectedException(Rejection rejection) {
      super("Gate pass " + rejection.name().toLowerCase().replace('_', ' '));
      this.rejection = rejection;
    }

    public Rejection rejection() {
      return rejection;
    }
  }
}
