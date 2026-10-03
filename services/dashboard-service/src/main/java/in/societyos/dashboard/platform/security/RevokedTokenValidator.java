package in.societyos.dashboard.platform.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects tokens on the Redis deny-list ({@code jwt:deny:{jti}}), written by identity-service on
 * logout, device revoke and role change. Fails open if Redis is down: tokens live 15 min.
 */
public class RevokedTokenValidator implements OAuth2TokenValidator<Jwt> {

  public static final String PREFIX = "jwt:deny:";

  private final StringRedisTemplate redis;

  public RevokedTokenValidator(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    String jti = jwt.getId();
    if (jti == null) {
      return OAuth2TokenValidatorResult.success();
    }
    try {
      if (Boolean.TRUE.equals(redis.hasKey(PREFIX + jti))) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token revoked", null));
      }
    } catch (RuntimeException e) {
      // fail open; the token expires soon
    }
    return OAuth2TokenValidatorResult.success();
  }
}
