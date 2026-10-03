package in.societyos.billing.platform.security;

import in.societyos.billing.platform.core.Hashing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Application-level encryption for PII columns (docs/architecture/05 §6): AES-256-GCM for the
 * value ({@code *_enc}) and a keyed HMAC for lookups ({@code *_hash}). In production the keys
 * are KMS data keys; locally they come from configuration.
 */
public class FieldCrypto {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int IV_BYTES = 12;

  private final SecretKeySpec key;
  private final byte[] hashKey;

  public FieldCrypto(String base64Key, String base64HashKey) {
    byte[] k = Base64.getDecoder().decode(base64Key);
    if (k.length != 32) {
      throw new IllegalArgumentException("sos.crypto.field-key must be 32 bytes (base64)");
    }
    this.key = new SecretKeySpec(k, "AES");
    this.hashKey = Base64.getDecoder().decode(base64HashKey);
  }

  public String encrypt(String plain) {
    if (plain == null) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String decrypt(String stored) {
    if (stored == null) {
      return null;
    }
    if (!stored.startsWith("v1:")) {
      throw new IllegalArgumentException("Unknown cipher version");
    }
    try {
      byte[] all = Base64.getDecoder().decode(stored.substring(3));
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_BYTES));
      return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Deterministic keyed hash for equality lookups (e.g. find staff by phone at the gate). */
  public String hash(String value) {
    return value == null ? null : Hashing.hmacSha256Hex(hashKey, value);
  }
}
