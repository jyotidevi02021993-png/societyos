package in.societyos.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer expected {@code iss}
 * @param jwksUri identity-service JWKS
 * @param publicPaths reachable without a token (login, OTP, JWKS, payment webhooks)
 * @param allowedOrigins CORS origins of the web portals
 */
@ConfigurationProperties(prefix = "sos.gateway")
public record GatewaySecurityProperties(
    String issuer, String jwksUri, List<String> publicPaths, List<String> allowedOrigins) {

  public GatewaySecurityProperties {
    publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }
}
