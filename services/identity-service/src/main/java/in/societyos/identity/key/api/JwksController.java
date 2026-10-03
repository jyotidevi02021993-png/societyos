package in.societyos.identity.key.api;

import in.societyos.identity.key.application.SigningKeyService;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public keys for every service and the gateway to verify tokens. */
@RestController
class JwksController {

  private final SigningKeyService keys;

  JwksController(SigningKeyService keys) {
    this.keys = keys;
  }

  @GetMapping(value = "/.well-known/jwks.json", produces = "application/json")
  ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
        .body(keys.publicJwks().toJSONObject());
  }
}
