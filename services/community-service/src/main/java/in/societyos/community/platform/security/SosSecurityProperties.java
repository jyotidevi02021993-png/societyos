package in.societyos.community.platform.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sos.security")
public class SosSecurityProperties {

  /** Token issuer ({@code iss}); tokens from anyone else are rejected. */
  private String issuer = "https://auth.societyos.in";

  /** JWKS of identity-service. With the registry: {@code http://identity-service/.well-known/jwks.json}. */
  private String jwksUri = "http://localhost:8081/.well-known/jwks.json";

  /** Base URL of identity-service for permission lookups ({@code lb://} style names work with the registry). */
  private String identityUrl = "http://identity-service";

  /** Ant patterns reachable without a token, in addition to actuator health and API docs. */
  private List<String> publicPaths = new ArrayList<>();

  /** How long resolved permissions are cached in Redis. */
  private Duration permissionCacheTtl = Duration.ofMinutes(10);

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public String getJwksUri() {
    return jwksUri;
  }

  public void setJwksUri(String jwksUri) {
    this.jwksUri = jwksUri;
  }

  public String getIdentityUrl() {
    return identityUrl;
  }

  public void setIdentityUrl(String identityUrl) {
    this.identityUrl = identityUrl;
  }

  public List<String> getPublicPaths() {
    return publicPaths;
  }

  public void setPublicPaths(List<String> publicPaths) {
    this.publicPaths = publicPaths;
  }

  public Duration getPermissionCacheTtl() {
    return permissionCacheTtl;
  }

  public void setPermissionCacheTtl(Duration permissionCacheTtl) {
    this.permissionCacheTtl = permissionCacheTtl;
  }
}
