package in.societyos.identity.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

  /** RFC 6238 appendix B test vector for SHA-1 (8 digits → last 6). */
  @Test
  void matchesRfc6238Vector() {
    byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    assertThat(Totp.code(key, 59 / 30)).isEqualTo("287082");
    assertThat(Totp.code(key, 1111111109L / 30)).isEqualTo("081804");
  }

  @Test
  void base32RoundTripAndVerify() {
    String secret = Totp.newSecret();
    byte[] key = Totp.unbase32(secret);
    assertThat(Totp.base32(key)).isEqualTo(secret);
    Instant now = Instant.now();
    String code = Totp.code(key, now.getEpochSecond() / 30);
    assertThat(Totp.verify(secret, code, now)).isTrue();
    assertThat(Totp.verify(secret, code, now.plusSeconds(120))).isFalse();
    assertThat(Totp.verify(secret, "12ab56", now)).isFalse();
  }
}
