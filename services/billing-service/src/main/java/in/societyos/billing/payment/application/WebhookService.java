package in.societyos.billing.payment.application;

import in.societyos.billing.payment.infrastructure.WebhookInbox;
import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.platform.core.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gateway webhooks (public endpoint, no token): verify the signature, find the society that owns
 * the order, then in one RLS-scoped transaction record the event id (dedup) and settle or fail
 * the payment. Redeliveries and unknown orders are acknowledged without effect.
 */
@Service
public class WebhookService {

  public enum Result { PROCESSED, DUPLICATE, IGNORED }

  private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

  private final PaymentService payments;
  private final WebhookInbox inbox;
  private final TransactionTemplate tx;

  public WebhookService(PaymentService payments, WebhookInbox inbox, PlatformTransactionManager txManager) {
    this.payments = payments;
    this.inbox = inbox;
    this.tx = new TransactionTemplate(txManager);
  }

  public Result handle(String provider, Map<String, String> headers, String rawBody) {
    PaymentGateway gateway = payments.gateway(provider);
    PaymentGateway.WebhookEvent event;
    try {
      event = gateway.verifyWebhook(headers, rawBody);
    } catch (SecurityException e) {
      throw new ProblemException("INVALID_SIGNATURE", HttpStatus.UNAUTHORIZED, "Webhook signature is not valid");
    }
    if (event.eventId() == null || event.eventId().isBlank()) {
      throw ProblemException.badRequest("INVALID_WEBHOOK", "Webhook has no event id");
    }
    if (event.outcome() == PaymentGateway.Outcome.IGNORED || event.orderId() == null) {
      return Result.IGNORED;
    }
    UUID society = inbox.societyOfOrder(event.orderId()).orElse(null);
    if (society == null) {
      log.warn("Webhook {} {} for unknown order {}", provider, event.eventId(), event.orderId());
      return Result.IGNORED;
    }
    Result[] result = {Result.DUPLICATE};
    TenantContext.runAs(society, () -> tx.executeWithoutResult(s -> {
      if (!inbox.record(provider, event.eventId(), society, rawBody)) {
        return;
      }
      result[0] = Result.PROCESSED;
      if (event.outcome() == PaymentGateway.Outcome.CAPTURED) {
        payments.captured(event.orderId(), event.gatewayPaymentId(), event.amountPaise());
      } else {
        payments.failed(event.orderId(), event.reason());
      }
    }));
    return result[0];
  }
}
