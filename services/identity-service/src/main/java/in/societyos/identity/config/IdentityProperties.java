package in.societyos.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sos.identity")
public record IdentityProperties(
    @DefaultValue("https://auth.societyos.in") String issuer,
    @DefaultValue("15m") Duration accessTokenTtl,
    @DefaultValue("30d") Duration refreshTokenTtl,
    @DefaultValue Otp otp,
    @DefaultValue BootstrapAdmin bootstrapAdmin,
    java.util.Map<String, ServiceClient> serviceClients) {

  public IdentityProperties {
    serviceClients = serviceClients == null ? java.util.Map.of() : java.util.Map.copyOf(serviceClients);
  }

  /**
   * A service allowed to obtain service tokens (OAuth2 client-credentials style).
   *
   * @param secretSha256 hex SHA-256 of the client secret (secrets are random 32+ byte values,
   *     so a fast hash is enough; the plain secret is never in config)
   * @param scopes what the token may do, e.g. {@code users:contact}
   */
  public record ServiceClient(String secretSha256, java.util.List<String> scopes) {}

  public record Otp(
      @DefaultValue("5m") Duration ttl,
      @DefaultValue("5") int maxAttempts,
      @DefaultValue("3") int maxRequestsPerWindow,
      @DefaultValue("15m") Duration requestWindow,
      String devCode) {

    public boolean devMode() {
      return devCode != null && !devCode.isBlank();
    }
  }

  public record BootstrapAdmin(String email, String password, @DefaultValue("Platform Admin") String name) {

    public boolean configured() {
      return email != null && !email.isBlank() && password != null && !password.isBlank();
    }
  }
}
