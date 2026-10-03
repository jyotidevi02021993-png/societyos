package in.societyos.inventory.platform.test;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Stands in for identity-service in tests: an RSA key, a tiny HTTP server serving its JWKS, and
 * a method to mint tokens with the SocietyOS claims. The service under test validates these
 * tokens exactly as it validates real ones.
 */
public final class TestJwtIssuer {

  public static final String ISSUER = "https://auth.societyos.in";

  private static final RSAKey KEY;
  private static final HttpServer SERVER;

  static {
    try {
      KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
      SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      SERVER.createContext("/.well-known/jwks.json", ex -> {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
      });
      SERVER.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private TestJwtIssuer() {}

  public static String jwksUri() {
    return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/.well-known/jwks.json";
  }

  /** Token for a user acting in one society. */
  public static String token(UUID userId, UUID societyId, String... roles) {
    return token(userId, societyId, List.of(societyId), roles);
  }

  public static String token(UUID userId, UUID activeSociety, List<UUID> societies, String... roles) {
    try {
      Instant now = Instant.now();
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .issuer(ISSUER)
              .subject(userId.toString())
              .jwtID(UUID.randomUUID().toString())
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(900)))
              .claim("typ", "USER")
              .claim("sids", societies.stream().map(UUID::toString).toList())
              .claim("roles", List.of(roles))
              .claim("pv", 1);
      if (activeSociety != null) {
        claims.claim("sid", activeSociety.toString());
      }
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(JOSEObjectType.JWT).build(),
              claims.build());
      jwt.sign(new RSASSASigner(KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
