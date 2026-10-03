package in.societyos.identity.key.application;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import in.societyos.identity.platform.core.UuidV7;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * RS256 signing keys. The newest non-retired key signs; the JWKS publishes every key that is
 * not retired so tokens signed just before a rotation still verify. Keys live in
 * {@code signing_key} so all replicas share them (private keys are KMS-encrypted in prod).
 */
@Service
public class SigningKeyService {

  private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

  private final JdbcTemplate jdbc;
  private volatile List<RSAKey> keys = List.of();

  public SigningKeyService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Newest first. Generates the first key on an empty database. */
  public synchronized void load() {
    List<RSAKey> loaded = query();
    if (loaded.isEmpty()) {
      generate();
      loaded = query();
    }
    keys = loaded;
  }

  @Scheduled(fixedDelayString = "PT10M")
  public void refresh() {
    keys = query();
  }

  public RSAKey signingKey() {
    if (keys.isEmpty()) {
      load();
    }
    return keys.getFirst();
  }

  public JWKSet publicJwks() {
    if (keys.isEmpty()) {
      load();
    }
    return new JWKSet(keys.stream().map(k -> (com.nimbusds.jose.jwk.JWK) k.toPublicJWK()).toList());
  }

  public JWKSet privateJwks() {
    return new JWKSet(signingKey());
  }

  /** Creates a new key; it signs from the next load. Old keys stay in the JWKS until retired. */
  public void rotate() {
    generate();
    load();
  }

  private void generate() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      KeyPair pair = gen.generateKeyPair();
      String kid = UuidV7.next().toString();
      jdbc.update(
          "insert into signing_key (kid, private_key_pem, public_key_pem) values (?, ?, ?)",
          kid,
          Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
          Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
      log.info("Generated JWT signing key {}", kid);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<RSAKey> query() {
    List<RSAKey> result = new ArrayList<>();
    jdbc.query(
        "select kid, private_key_pem, public_key_pem from signing_key where retired_at is null order by created_at desc",
        rs -> {
          result.add(toJwk(rs.getString("kid"), rs.getString("private_key_pem"), rs.getString("public_key_pem")));
        });
    return result;
  }

  private static RSAKey toJwk(String kid, String privateB64, String publicB64) {
    try {
      KeyFactory kf = KeyFactory.getInstance("RSA");
      RSAPublicKey pub =
          (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)));
      RSAPrivateKey priv =
          (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64)));
      return new RSAKey.Builder(pub)
          .privateKey(priv)
          .keyID(kid)
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(JWSAlgorithm.RS256)
          .build();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Corrupt signing key " + kid, e);
    }
  }
}
