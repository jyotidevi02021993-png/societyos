package in.societyos.gateway;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Circuit-breaker fallback: a clear 503 instead of a hanging request when a service is down. */
@RestController
class FallbackController {

  @RequestMapping("/fallback/{service}")
  Mono<ResponseEntity<Map<String, Object>>> fallback(@PathVariable String service) {
    return Mono.just(
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(Map.of(
                "status", 503,
                "code", "SERVICE_UNAVAILABLE",
                "title", "SERVICE_UNAVAILABLE",
                "detail", service + " is temporarily unavailable, please retry",
                "service", service)));
  }
}
