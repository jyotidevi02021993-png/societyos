package in.societyos.workflow.platform.web;

import in.societyos.workflow.platform.core.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes writes safe to retry. A POST/PUT/PATCH with an {@code Idempotency-Key} header is executed
 * once per (service, user, key) for 24 h; replays get the stored response. Mobile offline sync
 * and payment creation always send the header. Redis being down fails open (request runs).
 */
public class IdempotencyFilter extends OncePerRequestFilter {

  public static final String HEADER = "Idempotency-Key";
  private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
  private static final String PENDING = "PENDING";
  private static final Duration TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final String service;

  public IdempotencyFilter(StringRedisTemplate redis, JsonMapper mapper, String service) {
    this.redis = redis;
    this.mapper = mapper;
    this.service = service;
  }

  record Stored(int status, String contentType, String body) {}

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String m = request.getMethod();
    return request.getHeader(HEADER) == null
        || !("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m));
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (key.length() > 128) {
      response.sendError(HttpStatus.BAD_REQUEST.value(), "Idempotency-Key too long");
      return;
    }
    String user = TenantContext.userId().map(Object::toString).orElse("anon");
    String redisKey = "idem:" + service + ":" + user + ":" + key;

    String existing;
    Boolean claimed;
    try {
      existing = redis.opsForValue().get(redisKey);
      claimed = existing == null ? redis.opsForValue().setIfAbsent(redisKey, PENDING, TTL) : Boolean.FALSE;
      if (existing == null && !Boolean.TRUE.equals(claimed)) {
        existing = redis.opsForValue().get(redisKey);
      }
    } catch (RuntimeException e) {
      log.warn("Redis unavailable, running request without idempotency: {}", e.getMessage());
      chain.doFilter(request, response);
      return;
    }

    if (existing != null) {
      replay(existing, response);
      return;
    }

    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    boolean stored = false;
    try {
      chain.doFilter(request, wrapper);
      int status = wrapper.getStatus();
      if (status < 500) {
        String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        redis.opsForValue()
            .set(redisKey, mapper.writeValueAsString(new Stored(status, wrapper.getContentType(), body)), TTL);
        stored = true;
      }
    } finally {
      if (!stored) {
        try {
          redis.delete(redisKey); // failed or crashed: allow a retry
        } catch (RuntimeException ignored) {
          // key expires anyway
        }
      }
      wrapper.copyBodyToResponse();
    }
  }

  private void replay(String existing, HttpServletResponse response) throws IOException {
    if (PENDING.equals(existing)) {
      response.setStatus(HttpStatus.CONFLICT.value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response.getWriter()
          .write(mapper.writeValueAsString(Map.of(
              "code", "IDEMPOTENCY_IN_PROGRESS",
              "status", 409,
              "detail", "A request with this Idempotency-Key is still running")));
      return;
    }
    Stored s = mapper.readValue(existing, Stored.class);
    response.setStatus(s.status());
    if (s.contentType() != null) {
      response.setContentType(s.contentType());
    }
    response.setHeader("Idempotent-Replayed", "true");
    response.getOutputStream().write(s.body().getBytes(StandardCharsets.UTF_8));
  }
}
