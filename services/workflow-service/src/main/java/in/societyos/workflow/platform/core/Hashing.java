package in.societyos.workflow.platform.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Hashes for lookups and logs, so raw PII never lands in keys, logs or events. */
public final class Hashing {

  private Hashing() {}

  public static String sha256Hex(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Deterministic keyed hash (e.g. {@code phone_hash} columns). */
  public static String hmacSha256Hex(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Short, non-reversible id for log lines ({@code userIdHash}). */
  public static String shortHash(Object value) {
    return value == null ? "-" : sha256Hex(value.toString()).substring(0, 12);
  }

  /** 98XXXXXX21: for guards, vendors and anyone who must not see a full number. */
  public static String maskPhone(String phone) {
    if (phone == null || phone.length() < 6) {
      return "XXXX";
    }
    String digits = phone.startsWith("+91") ? phone.substring(3) : phone;
    if (digits.length() < 4) {
      return "XXXX";
    }
    return digits.substring(0, 2) + "X".repeat(digits.length() - 4) + digits.substring(digits.length() - 2);
  }
}
