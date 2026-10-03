package in.societyos.identity.auth.application;

import in.societyos.identity.config.IdentityProperties;
import in.societyos.identity.user.domain.AppUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Builds the 15-minute RS256 access token (claims in docs/architecture/05 §2). */
@Service
public class TokenService {

  public record AccessToken(String value, String jti, Instant expiresAt) {}

  private final JwtEncoder encoder;
  private final IdentityProperties props;

  public TokenService(JwtEncoder encoder, IdentityProperties props) {
    this.encoder = encoder;
    this.props = props;
  }

  /**
   * @param societies every society of the user → role codes there
   * @param activeSocietyId where writes go; null when the user has no society yet
   * @param amr how the user authenticated: otp, pwd, mfa
   */
  public AccessToken issue(
      AppUser user, UUID deviceId, Map<UUID, Set<String>> societies, UUID activeSocietyId, List<String> amr) {
    Instant now = Instant.now();
    Instant exp = now.plus(props.accessTokenTtl());
    String jti = UUID.randomUUID().toString();

    Set<String> roles = new TreeSet<>(activeSocietyId == null ? Set.of() : societies.getOrDefault(activeSocietyId, Set.of()));
    if (user.isPlatformAdmin()) {
      roles.add(AppUser.SUPER_ADMIN);
    }
    List<String> sids = new ArrayList<>(societies.keySet().stream().map(UUID::toString).toList());
    if (activeSocietyId != null && !sids.contains(activeSocietyId.toString())) {
      sids.add(activeSocietyId.toString()); // platform admin working in a society they are not a member of
    }

    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(props.issuer())
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(exp)
            .id(jti)
            .claim("typ", "USER")
            .claim("sids", sids)
            .claim("roles", List.copyOf(roles))
            .claim("pv", user.getPermissionVersion())
            .claim("amr", amr)
            .claim("lang", user.getPreferredLang());
    if (activeSocietyId != null) {
      claims.claim("sid", activeSocietyId.toString());
    }
    if (deviceId != null) {
      claims.claim("did", deviceId.toString());
    }
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    return new AccessToken(token, jti, exp);
  }

  /** 5-minute service token: typ=SERVICE, sub=service:clientId, scopes only, no societies. */
  public AccessToken issueService(String clientId, List<String> scopes) {
    Instant now = Instant.now();
    Instant exp = now.plus(java.time.Duration.ofMinutes(5));
    String jti = UUID.randomUUID().toString();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(props.issuer())
            .subject("service:" + clientId)
            .issuedAt(now)
            .expiresAt(exp)
            .id(jti)
            .claim("typ", "SERVICE")
            .claim("scope", scopes)
            .claim("roles", List.of("SERVICE"))
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
    return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), jti, exp);
  }
}
