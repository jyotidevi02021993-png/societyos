package in.societyos.dashboard.platform.core;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RFC 9562 UUID version 7: 48-bit Unix millisecond timestamp followed by random bits, so ids
 * sort by creation time and index well. Monotonic within one JVM: ids created in the same
 * millisecond increase through the 12-bit counter in {@code rand_a}.
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final ReentrantLock LOCK = new ReentrantLock();
  private static final Clock CLOCK = Clock.systemUTC();

  private static long lastMillis = -1;
  private static int counter;

  private UuidV7() {}

  public static UUID next() {
    long millis;
    int seq;
    LOCK.lock();
    try {
      millis = CLOCK.millis();
      if (millis <= lastMillis) {
        millis = lastMillis;
        counter++;
        if (counter > 0xFFF) { // counter overflow: borrow the next millisecond
          millis++;
          counter = RANDOM.nextInt(0x400);
        }
      } else {
        counter = RANDOM.nextInt(0x400);
      }
      lastMillis = millis;
      seq = counter;
    } finally {
      LOCK.unlock();
    }
    long msb = (millis << 16) | 0x7000L | (seq & 0xFFFL);
    long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }

  /** Milliseconds since the epoch encoded in a v7 id. */
  public static long timestampMillis(UUID id) {
    if (id.version() != 7) {
      throw new IllegalArgumentException("Not a UUIDv7: " + id);
    }
    return id.getMostSignificantBits() >>> 16;
  }
}
