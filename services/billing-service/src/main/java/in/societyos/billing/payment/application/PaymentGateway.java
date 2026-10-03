package in.societyos.billing.payment.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A payment gateway (hosted checkout, so card and UPI data never reach us). The built-in
 * {@code stub} provider stands in until a real one (Razorpay) is plugged in: implement this
 * interface as a Spring bean with its own {@link #provider()} name, and its webhooks arrive at
 * {@code POST /v1/webhooks/<provider>}.
 */
public interface PaymentGateway {

  /** What the client needs to open the hosted checkout. */
  record Order(String orderId, String keyId, String checkoutUrl) {}

  enum Outcome { CAPTURED, FAILED, IGNORED }

  /** A verified webhook: {@code eventId} is the gateway's id, used for deduplication. */
  record WebhookEvent(String eventId, Outcome outcome, String orderId, String gatewayPaymentId, long amountPaise,
      String reason) {}

  /** Status of an order as the gateway reports it (reconciliation of lost webhooks). */
  record OrderStatus(Outcome outcome, String gatewayPaymentId, long amountPaise, String reason) {}

  String provider();

  Order createOrder(UUID paymentId, long amountPaise, Map<String, String> notes);

  /**
   * Verifies the signature over the raw body and parses it.
   *
   * @throws SecurityException when the signature is missing or wrong
   */
  WebhookEvent verifyWebhook(Map<String, String> headers, String rawBody);

  /** Empty when the gateway has nothing final to report yet. */
  Optional<OrderStatus> fetchStatus(String orderId);
}
