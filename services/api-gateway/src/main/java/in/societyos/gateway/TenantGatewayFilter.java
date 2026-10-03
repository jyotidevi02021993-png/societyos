package in.societyos.gateway;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * For authenticated requests: rejects revoked tokens (Redis {@code jwt:deny:{jti}}), checks that
 * {@code X-Society-Id} is one of the token's societies, strips spoofable identity headers and
 * forwards {@code X-User-Id} and a W3C {@code traceparent}. Services still re-validate the JWT.
 */
@Component
class TenantGatewayFilter implements GlobalFilter, Ordered {

  private static final Logger log = LoggerFactory.getLogger(TenantGatewayFilter.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  static final String SOCIETY_HEADER = "X-Society-Id";
  static final String USER_HEADER = "X-User-Id";

  private final ReactiveStringRedisTemplate redis;

  TenantGatewayFilter(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest.Builder req =
        exchange.getRequest().mutate().headers(h -> h.remove(USER_HEADER));
    if (exchange.getRequest().getHeaders().getFirst("traceparent") == null) {
      req.header("traceparent", newTraceparent());
    }
    ServerWebExchange base = exchange.mutate().request(req.build()).build();

    return base.getPrincipal()
        .filter(JwtAuthenticationToken.class::isInstance)
        .cast(JwtAuthenticationToken.class)
        .flatMap(auth -> checkAndForward(base, chain, auth.getToken()).thenReturn(Boolean.TRUE))
        .switchIfEmpty(Mono.defer(() -> chain.filter(base).thenReturn(Boolean.FALSE)))
        .then();
  }

  private Mono<Void> checkAndForward(ServerWebExchange exchange, GatewayFilterChain chain, Jwt jwt) {
    String society = exchange.getRequest().getHeaders().getFirst(SOCIETY_HEADER);
    if (society != null && !society.isBlank() && !allowedSocieties(jwt).contains(society.trim())) {
      return reject(exchange.getResponse(), HttpStatus.FORBIDDEN, "SOCIETY_NOT_ALLOWED", "Token does not grant this society");
    }
    Mono<Boolean> revoked =
        jwt.getId() == null
            ? Mono.just(false)
            : redis.hasKey("jwt:deny:" + jwt.getId())
                .onErrorResume(e -> {
                  log.debug("Deny-list unavailable, failing open: {}", e.getMessage());
                  return Mono.just(false);
                });
    return revoked.flatMap(
        isRevoked -> {
          if (isRevoked) {
            return reject(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token has been revoked");
          }
          ServerHttpRequest forwarded =
              exchange.getRequest().mutate().header(USER_HEADER, jwt.getSubject()).build();
          return chain.filter(exchange.mutate().request(forwarded).build());
        });
  }

  static List<String> allowedSocieties(Jwt jwt) {
    List<String> result = new ArrayList<>();
    List<String> sids = jwt.getClaimAsStringList("sids");
    if (sids != null) {
      result.addAll(sids);
    }
    String sid = jwt.getClaimAsString("sid");
    if (sid != null) {
      result.add(sid);
    }
    return result;
  }

  static Mono<Void> reject(ServerHttpResponse response, HttpStatus status, String code, String detail) {
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    String body = "{\"status\":%d,\"code\":\"%s\",\"title\":\"%s\",\"detail\":\"%s\"}"
        .formatted(status.value(), code, code, detail);
    DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }

  private static String newTraceparent() {
    byte[] trace = new byte[16];
    byte[] span = new byte[8];
    RANDOM.nextBytes(trace);
    RANDOM.nextBytes(span);
    return "00-" + HexFormat.of().formatHex(trace) + "-" + HexFormat.of().formatHex(span) + "-01";
  }

  /** After Spring Security has authenticated, before routing. */
  @Override
  public int getOrder() {
    return -1;
  }
}
