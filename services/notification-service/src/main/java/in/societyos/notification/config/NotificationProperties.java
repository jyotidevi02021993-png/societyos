package in.societyos.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sos.notification.*}: identity client credentials, dispatch cadence, retry backoff and
 * the channel adapters in use.
 */
@ConfigurationProperties("sos.notification")
public record NotificationProperties(
    String identityUrl,
    String clientId,
    String clientSecret,
    Duration dispatchPoll,
    String retentionCron,
    Retry retry,
    Email email,
    Channels channels) {

  public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {}

  public record Email(String from) {}

  public record Channels(String push, String sms, String whatsapp, String email) {}

  public NotificationProperties {
    dispatchPoll = dispatchPoll == null ? Duration.ofSeconds(2) : dispatchPoll;
    retentionCron = retentionCron == null ? "0 15 3 * * *" : retentionCron;
    retry = retry == null ? new Retry(5, Duration.ofSeconds(30), Duration.ofMinutes(30)) : retry;
    email = email == null ? new Email("no-reply@societyos.in") : email;
    channels = channels == null ? new Channels("log", "log", "log", "smtp") : channels;
  }
}
