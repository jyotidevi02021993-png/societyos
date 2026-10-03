package in.societyos.identity.auth.application;

import in.societyos.identity.config.IdentityProperties;
import in.societyos.identity.platform.core.Hashing;
import in.societyos.identity.platform.core.error.ProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Client-credentials tokens for service-to-service calls made without a user (e.g.
 * notification-service resolving a recipient's contact while handling a Kafka event).
 */
@Service
public class ServiceTokenService {

  private final IdentityProperties props;
  private final TokenService tokens;

  public ServiceTokenService(IdentityProperties props, TokenService tokens) {
    this.props = props;
    this.tokens = tokens;
  }

  public TokenService.AccessToken issue(String clientId, String clientSecret, List<String> requestedScopes) {
    IdentityProperties.ServiceClient client = props.serviceClients().get(clientId);
    if (client == null || clientSecret == null || !matches(client.secretSha256(), clientSecret)) {
      throw new ProblemException("INVALID_CLIENT", HttpStatus.UNAUTHORIZED, "Unknown client or wrong secret");
    }
    List<String> allowed = client.scopes() == null ? List.of() : client.scopes();
    List<String> scopes = requestedScopes == null || requestedScopes.isEmpty() ? allowed : requestedScopes;
    if (!allowed.containsAll(scopes)) {
      throw ProblemException.forbidden("SCOPE_NOT_ALLOWED", "Client may not request " + scopes);
    }
    return tokens.issueService(clientId, scopes);
  }

  private static boolean matches(String expectedHex, String secret) {
    return expectedHex != null
        && MessageDigest.isEqual(
            expectedHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
            Hashing.sha256Hex(secret).getBytes(StandardCharsets.US_ASCII));
  }
}
