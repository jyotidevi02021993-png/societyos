package in.societyos.identity.auth.application;

import in.societyos.identity.config.IdentityProperties;
import in.societyos.identity.platform.core.Hashing;
import in.societyos.identity.platform.core.error.ProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Phone OTP challenges in Redis (docs/architecture/04 #1): {@code otp:{phoneHash}} holds the hashed
 * code and attempt count for 5 minutes; {@code otp:rl:{phoneHash}} allows 3 requests per 15
 * minutes. Keys never contain the raw phone number.
 */
@Service
public class OtpService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final StringRedisTemplate redis;
  private final SmsSender sms;
  private final IdentityProperties.Otp props;

  public OtpService(StringRedisTemplate redis, SmsSender sms, IdentityProperties props) {
    this.redis = redis;
    this.sms = sms;
    this.props = props.otp();
  }

  /** @return seconds until the code expires */
  public long request(String phoneE164, String lang) {
    String phoneHash = Hashing.sha256Hex(phoneE164);
    String rateKey = "otp:rl:" + phoneHash;
    Long count = redis.opsForValue().increment(rateKey);
    if (count != null && count == 1) {
      redis.expire(rateKey, props.requestWindow());
    }
    if (count != null && count > props.maxRequestsPerWindow()) {
      throw new ProblemException(
          "OTP_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many OTP requests, try again later");
    }

    String code = props.devMode() ? props.devCode() : "%06d".formatted(RANDOM.nextInt(1_000_000));
    String key = "otp:" + phoneHash;
    redis.opsForHash().putAll(key, Map.of("code", hash(code, phoneHash), "attempts", "0"));
    redis.expire(key, props.ttl());
    sms.sendOtp(phoneE164, code, lang);
    return props.ttl().toSeconds();
  }

  /** Throws unless the code matches; a verified challenge is deleted (single use). */
  public void verify(String phoneE164, String code) {
    String phoneHash = Hashing.sha256Hex(phoneE164);
    String key = "otp:" + phoneHash;
    Map<Object, Object> challenge = redis.opsForHash().entries(key);
    if (challenge.isEmpty()) {
      throw ProblemException.badRequest("OTP_EXPIRED", "The code has expired, request a new one");
    }
    int attempts = Integer.parseInt(String.valueOf(challenge.getOrDefault("attempts", "0")));
    if (attempts >= props.maxAttempts()) {
      redis.delete(key);
      throw new ProblemException("OTP_LOCKED", HttpStatus.TOO_MANY_REQUESTS, "Too many wrong codes, request a new one");
    }
    byte[] expected = String.valueOf(challenge.get("code")).getBytes(StandardCharsets.UTF_8);
    byte[] actual = hash(code == null ? "" : code.trim(), phoneHash).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, actual)) {
      redis.opsForHash().increment(key, "attempts", 1);
      throw ProblemException.badRequest("OTP_INVALID", "The code is not correct");
    }
    redis.delete(key);
  }

  private static String hash(String code, String phoneHash) {
    return Hashing.sha256Hex(code + ":" + phoneHash);
  }

  Duration ttl() {
    return props.ttl();
  }
}
