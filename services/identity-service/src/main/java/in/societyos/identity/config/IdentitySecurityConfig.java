package in.societyos.identity.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import in.societyos.identity.key.application.SigningKeyService;
import in.societyos.identity.platform.security.SosSecurityProperties;
import in.societyos.identity.platform.security.config.PlatformSecurityConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * identity-service is the token issuer, so it verifies tokens with its own keys instead of
 * fetching its own JWKS over HTTP.
 */
@Configuration
class IdentitySecurityConfig {

  private final SigningKeyService keys;

  IdentitySecurityConfig(SigningKeyService keys) {
    this.keys = keys;
  }

  @EventListener(ApplicationReadyEvent.class)
  void loadKeys() {
    keys.load();
  }

  @Bean
  JwtEncoder jwtEncoder() {
    JWKSource<SecurityContext> source = (selector, ctx) -> selector.select(keys.privateJwks());
    return new NimbusJwtEncoder(source);
  }

  @Bean
  JwtDecoder jwtDecoder(SosSecurityProperties props, ObjectProvider<StringRedisTemplate> redis) {
    JWKSource<SecurityContext> source = (selector, ctx) -> selector.select(keys.publicJwks());
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    decoder.setJwtValidator(PlatformSecurityConfig.validator(props, redis.getIfAvailable()));
    return decoder;
  }

  /** Argon2id for admin passwords (docs/architecture/05 §1). */
  @Bean
  PasswordEncoder passwordEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }
}
