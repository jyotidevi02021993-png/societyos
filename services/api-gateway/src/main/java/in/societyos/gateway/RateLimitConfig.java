package in.societyos.gateway;

import java.net.InetSocketAddress;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/** Rate-limit keys: per user when authenticated, otherwise per client IP. */
@Configuration
class RateLimitConfig {

  @Bean
  @Primary
  KeyResolver userKeyResolver() {
    return exchange ->
        exchange.getPrincipal()
            .map(p -> "user:" + p.getName())
            .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange.getRequest().getRemoteAddress(),
                exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"))));
  }

  /** OTP endpoints are limited by IP no matter who asks. */
  @Bean
  KeyResolver ipKeyResolver() {
    return exchange ->
        Mono.just("ip:" + clientIp(exchange.getRequest().getRemoteAddress(),
            exchange.getRequest().getHeaders().getFirst("X-Forwarded-For")));
  }

  static String clientIp(InetSocketAddress remote, String forwardedFor) {
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim(); // first hop, set by the ALB
    }
    return Optional.ofNullable(remote).map(a -> a.getAddress().getHostAddress()).orElse("unknown");
  }
}
