package in.societyos.ticket.platform.security;

import in.societyos.ticket.platform.core.Hashing;
import in.societyos.ticket.platform.core.tenant.Tenant;
import in.societyos.ticket.platform.core.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the {@link TenantContext} from the validated JWT. {@code X-Society-Id} may pick any
 * society in the token's {@code sids}; otherwise the token's {@code sid} is active. A society the
 * token does not grant is rejected with 403 before any controller runs.
 */
public class TenantResolverFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth)) {
      chain.doFilter(request, response);
      return;
    }
    Jwt jwt = auth.getToken();
    List<UUID> readable = uuids(jwt.getClaimAsStringList(SosClaims.SOCIETIES));
    UUID active = uuidOrNull(jwt.getClaimAsString(SosClaims.SOCIETY));
    if (active != null && !readable.contains(active)) {
      readable = new java.util.ArrayList<>(readable);
      readable.add(active);
    }

    String header = request.getHeader(SosClaims.SOCIETY_HEADER);
    if (header != null && !header.isBlank()) {
      UUID requested;
      try {
        requested = UUID.fromString(header.trim());
      } catch (IllegalArgumentException e) {
        reject(response, HttpStatus.BAD_REQUEST, "INVALID_SOCIETY_ID", "X-Society-Id is not a UUID");
        return;
      }
      if (!readable.contains(requested)) {
        reject(response, HttpStatus.FORBIDDEN, "SOCIETY_NOT_ALLOWED", "Token does not grant this society");
        return;
      }
      active = requested;
    }

    Set<String> roles = new HashSet<>(orEmpty(jwt.getClaimAsStringList(SosClaims.ROLES)));
    Tenant.ActorType actorType =
        "SERVICE".equals(jwt.getClaimAsString(SosClaims.ACTOR_TYPE)) ? Tenant.ActorType.SERVICE : Tenant.ActorType.USER;
    Tenant tenant =
        new Tenant(uuidOrNull(jwt.getSubject()), actorType, active, readable, roles, jwt.getTokenValue());

    TenantContext.set(tenant);
    MDC.put("societyId", active == null ? "-" : active.toString());
    MDC.put("userIdHash", Hashing.shortHash(tenant.userId()));
    try {
      chain.doFilter(request, response);
    } finally {
      TenantContext.clear();
      MDC.remove("societyId");
      MDC.remove("userIdHash");
    }
  }

  private static void reject(HttpServletResponse response, HttpStatus status, String code, String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter()
        .write("{\"status\":%d,\"code\":\"%s\",\"title\":\"%s\",\"detail\":\"%s\",\"traceId\":\"%s\"}"
            .formatted(status.value(), code, code, detail, String.valueOf(MDC.get("traceId"))));
  }

  private static List<String> orEmpty(List<String> list) {
    return list == null ? List.of() : list;
  }

  private static List<UUID> uuids(List<String> values) {
    return orEmpty(values).stream().map(UUID::fromString).toList();
  }

  private static UUID uuidOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
