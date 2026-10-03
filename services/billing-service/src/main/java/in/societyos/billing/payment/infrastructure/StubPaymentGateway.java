package in.societyos.billing.payment.infrastructure;

import in.societyos.billing.payment.application.PaymentGateway;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Development/test gateway ({@code provider = stub}). Orders are local ids; webhooks are JSON
 * {@code {id, event: payment.captured|payment.failed, orderId, paymentId, amountPaise, reason}}
 * signed with {@code X-Sos-Signature: hex(HMAC-SHA256(webhookSecret, rawBody))}, the same scheme
 * Razorpay uses, so a real adapter only changes the payload mapping.
 */
@Component
class StubPaymentGateway implements PaymentGateway {

  static final String SIGNATURE_HEADER = "x-sos-signature";

  private final byte[] secret;
  private final JsonMapper json;

  StubPaymentGateway(@Value("${sos.billing.gateway.stub.webhook-secret:stub-webhook-secret}") String secret,
      JsonMapper json) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.json = json;
  }

  @Override
  public String provider() {
    return "stub";
  }

  @Override
  public Order createOrder(UUID paymentId, long amountPaise, Map<String, String> notes) {
    String orderId = "order_" + paymentId.toString().replace("-", "");
    return new Order(orderId, "stub_key", "https://checkout.invalid/stub/" + orderId);
  }

  @Override
  public WebhookEvent verifyWebhook(Map<String, String> headers, String rawBody) {
    String given = headers.get(SIGNATURE_HEADER);
    if (given == null || rawBody == null) {
      throw new SecurityException("missing signature");
    }
    byte[] expected = HexFormat.of().formatHex(hmac(rawBody)).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, given.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
      throw new SecurityException("bad signature");
    }
    JsonNode n = json.readTree(rawBody);
    Outcome outcome = switch (n.path("event").asString("")) {
      case "payment.captured" -> Outcome.CAPTURED;
      case "payment.failed" -> Outcome.FAILED;
      default -> Outcome.IGNORED;
    };
    return new WebhookEvent(n.path("id").asString(""), outcome, n.path("orderId").asString(null),
        n.path("paymentId").asString(null), n.path("amountPaise").asLong(0), n.path("reason").asString(null));
  }

  @Override
  public Optional<OrderStatus> fetchStatus(String orderId) {
    return Optional.empty();
  }

  byte[] hmac(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
