package in.societyos.identity.auth.application;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), compatible with Google/Microsoft Authenticator. */
public final class Totp {

  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {}

  public static String newSecret() {
    byte[] bytes = new byte[20];
    RANDOM.nextBytes(bytes);
    return base32(bytes);
  }

  public static String otpauthUri(String issuer, String account, String secret) {
    String enc = java.net.URLEncoder.encode(issuer + ":" + account, java.nio.charset.StandardCharsets.UTF_8);
    return "otpauth://totp/" + enc + "?secret=" + secret + "&issuer="
        + java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8) + "&digits=6&period=30";
  }

  /** Accepts the current code and one step either side for clock drift. */
  public static boolean verify(String secret, String code, Instant now) {
    if (secret == null || code == null || !code.matches("\\d{6}")) {
      return false;
    }
    long step = now.getEpochSecond() / 30;
    byte[] key = unbase32(secret);
    for (long s = step - 1; s <= step + 1; s++) {
      if (MessageDigest.isEqual(code(key, s).getBytes(), code.getBytes())) {
        return true;
      }
    }
    return false;
  }

  static String code(byte[] key, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = h[h.length - 1] & 0xF;
      int bin = ((h[offset] & 0x7F) << 24) | ((h[offset + 1] & 0xFF) << 16) | ((h[offset + 2] & 0xFF) << 8) | (h[offset + 3] & 0xFF);
      return "%06d".formatted(bin % 1_000_000);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String base32(byte[] data) {
    StringBuilder sb = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xFF);
      bits += 8;
      while (bits >= 5) {
        sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
    }
    return sb.toString();
  }

  static byte[] unbase32(String s) {
    String clean = s.replace("=", "").replace(" ", "").toUpperCase();
    ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
    int buffer = 0;
    int bits = 0;
    for (char c : clean.toCharArray()) {
      int v = BASE32.indexOf(c);
      if (v < 0) {
        throw new IllegalArgumentException("Invalid base32");
      }
      buffer = (buffer << 5) | v;
      bits += 5;
      if (bits >= 8) {
        out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
        bits -= 8;
      }
    }
    return java.util.Arrays.copyOf(out.array(), out.position());
  }
}
