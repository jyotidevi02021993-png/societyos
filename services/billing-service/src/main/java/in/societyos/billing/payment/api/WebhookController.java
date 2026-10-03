package in.societyos.billing.payment.api;

import in.societyos.billing.payment.application.WebhookService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/webhooks/{provider}}: public (no token), authenticated by the gateway's HMAC
 * signature over the raw body, deduplicated by the gateway event id. Always 200 once verified, so
 * the gateway stops retrying.
 */
@RestController
class WebhookController {

  private final WebhookService webhooks;

  WebhookController(WebhookService webhooks) {
    this.webhooks = webhooks;
  }

  record WebhookResponse(String result) {}

  @PostMapping(path = "/v1/webhooks/{provider}", consumes = "*/*")
  WebhookResponse receive(@PathVariable String provider, @RequestHeader HttpHeaders headers,
      @RequestBody String rawBody) {
    Map<String, String> h = new HashMap<>();
    headers.forEach((name, values) -> {
      if (!values.isEmpty()) {
        h.put(name.toLowerCase(), values.getFirst());
      }
    });
    return new WebhookResponse(webhooks.handle(provider, h, rawBody).name());
  }
}
