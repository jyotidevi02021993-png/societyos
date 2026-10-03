package in.societyos.realtime.socket.application;

import in.societyos.realtime.platform.core.tenant.Tenant;
import in.societyos.realtime.platform.core.tenant.TenantContext;
import in.societyos.realtime.platform.security.PermissionEvaluator;
import in.societyos.realtime.platform.security.SosClaims;
import in.societyos.realtime.socket.domain.DestinationPolicy;
import in.societyos.realtime.socket.domain.DestinationPolicy.Requirement;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP CONNECT frame with the SocietyOS JWT ({@code Authorization: Bearer …}
 * native header; browsers cannot set headers on the WebSocket handshake) and authorises every
 * SUBSCRIBE against {@link DestinationPolicy}. Clients never SEND: this is a push-only socket.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

  static final String TENANT = "sos.tenant";
  private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

  private final JwtDecoder jwtDecoder;
  private final PermissionEvaluator perm;

  public StompAuthInterceptor(JwtDecoder jwtDecoder, PermissionEvaluator perm) {
    this.jwtDecoder = jwtDecoder;
    this.perm = perm;
  }

  /** The socket user: the principal name is the user id, so {@code convertAndSendToUser(userId)} works. */
  public record SocketUser(UUID userId) implements Principal {
    @Override
    public String getName() {
      return userId.toString();
    }
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    switch (accessor.getCommand()) {
      case CONNECT -> connect(accessor);
      case SUBSCRIBE -> subscribe(accessor);
      case SEND -> throw new MessageDeliveryException("This socket is push-only");
      default -> { }
    }
    return message;
  }

  private void connect(StompHeaderAccessor accessor) {
    String header = Optional.ofNullable(accessor.getFirstNativeHeader("Authorization"))
        .orElse(accessor.getFirstNativeHeader("authorization"));
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      log.info("Refused CONNECT without a bearer token");
      throw new MessageDeliveryException("Missing bearer token on CONNECT");
    }
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(header.substring(7).trim());
    } catch (JwtException e) {
      throw new MessageDeliveryException("Invalid token");
    }
    Tenant tenant = tenantOf(jwt);
    if (tenant.userId() == null) {
      throw new MessageDeliveryException("Token has no user");
    }
    Map<String, Object> session = accessor.getSessionAttributes();
    if (session != null) {
      session.put(TENANT, tenant);
    }
    accessor.setUser(new SocketUser(tenant.userId()));
  }

  private void subscribe(StompHeaderAccessor accessor) {
    Tenant tenant = accessor.getSessionAttributes() == null ? null
        : (Tenant) accessor.getSessionAttributes().get(TENANT);
    if (tenant == null) {
      throw new MessageDeliveryException("Not connected");
    }
    String destination = accessor.getDestination();
    Requirement req = DestinationPolicy.requirementFor(destination)
        .orElseThrow(() -> new MessageDeliveryException("Unknown destination " + destination));
    if (req.isPrivate()) {
      return;
    }
    if (!tenant.readableSocietyIds().contains(req.societyId())) {
      log.info("Refused subscription to another society");
      throw new MessageDeliveryException("Society not allowed");
    }
    Tenant scoped = new Tenant(tenant.userId(), tenant.actorType(), req.societyId(), tenant.readableSocietyIds(),
        tenant.roles(), tenant.bearerToken());
    boolean allowed;
    try {
      allowed = TenantContext.callAs(scoped, () -> perm.hasAny(req.anyPermission().toArray(String[]::new)));
    } catch (Exception e) {
      throw new MessageDeliveryException("Permission check failed");
    }
    if (!allowed) {
      log.info("Refused subscription without permission");
      throw new MessageDeliveryException("Missing permission for " + destination);
    }
  }

  static Tenant tenantOf(Jwt jwt) {
    List<UUID> readable = new ArrayList<>();
    List<String> sids = jwt.getClaimAsStringList(SosClaims.SOCIETIES);
    if (sids != null) {
      sids.forEach(s -> readable.add(UUID.fromString(s)));
    }
    UUID active = uuidOrNull(jwt.getClaimAsString(SosClaims.SOCIETY));
    if (active != null && !readable.contains(active)) {
      readable.add(active);
    }
    List<String> roles = jwt.getClaimAsStringList(SosClaims.ROLES);
    Tenant.ActorType type =
        "SERVICE".equals(jwt.getClaimAsString(SosClaims.ACTOR_TYPE)) ? Tenant.ActorType.SERVICE : Tenant.ActorType.USER;
    return new Tenant(uuidOrNull(jwt.getSubject()), type, active, readable,
        roles == null ? java.util.Set.of() : new HashSet<>(roles), jwt.getTokenValue());
  }

  private static UUID uuidOrNull(String v) {
    try {
      return v == null || v.isBlank() ? null : UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
