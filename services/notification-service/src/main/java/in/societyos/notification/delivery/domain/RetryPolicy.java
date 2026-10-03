package in.societyos.notification.delivery.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Exponential backoff: initial, 2x, 4x... capped at {@code max}; gives up after {@code maxAttempts}. */
public record RetryPolicy(int maxAttempts, Duration initial, Duration max) {

  /** Next try after {@code attemptsMade} failed attempts, or empty when the delivery should fail. */
  public Optional<Instant> nextAttempt(int attemptsMade, Instant now) {
    if (attemptsMade >= maxAttempts) {
      return Optional.empty();
    }
    long factor = 1L << Math.min(20, Math.max(0, attemptsMade - 1));
    Duration wait = initial.multipliedBy(factor);
    return Optional.of(now.plus(wait.compareTo(max) > 0 ? max : wait));
  }
}
